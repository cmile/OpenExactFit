"""command line: listen for measurements, list and export them, explore the gatt table."""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from pathlib import Path

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

from . import protocol
from .store import Store

log = logging.getLogger("exactfit")

DEFAULT_DB = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "exactfit/measurements.sqlite"


def _matches(device: BLEDevice, adv, address: str | None) -> bool:
    if address:
        return device.address.lower() == address.lower()
    if protocol.MEASUREMENT_SERVICE_UUID in (adv.service_uuids or []):
        return True
    name = adv.local_name or device.name or ""
    return name.upper().startswith("BUA")


async def find_device(address: str | None, timeout: float) -> BLEDevice | None:
    log.info("scanning for %s (switch the monitor on now)", address or protocol.LOCAL_NAME)
    return await BleakScanner.find_device_by_filter(
        lambda d, a: _matches(d, a, address), timeout=timeout
    )


async def session(device: BLEDevice, store: Store | None) -> int:
    """stay connected for one use of the monitor, store every measurement it notifies."""
    disconnected = asyncio.Event()
    queue: asyncio.Queue[bytes] = asyncio.Queue()
    count = 0

    def on_disconnect(_client: BleakClient) -> None:
        disconnected.set()

    def on_notify(_char, data: bytearray) -> None:
        queue.put_nowait(bytes(data))

    async with BleakClient(device, disconnected_callback=on_disconnect) as client:
        log.info("connected to %s", device.address)
        await client.start_notify(protocol.MEASUREMENT_UUID, on_notify)
        waiter = asyncio.create_task(disconnected.wait())
        while not disconnected.is_set():
            getter = asyncio.create_task(queue.get())
            done, _ = await asyncio.wait({getter, waiter}, return_when=asyncio.FIRST_COMPLETED)
            if getter not in done:
                getter.cancel()
                break
            payload = getter.result()
            log.debug("raw %s", payload.hex())
            try:
                m = protocol.parse_measurement(payload)
            except protocol.ProtocolError as exc:
                log.warning("%s", exc)
                continue
            count += 1
            print(m, flush=True)
            if store is not None:
                if not store.add(m, device.address):
                    log.info("already stored")
        waiter.cancel()
    log.info("monitor disconnected after %d measurement(s)", count)
    return count


async def cmd_listen(args) -> int:
    store = None if args.no_store else Store(args.db)
    while True:
        device = await find_device(args.address, args.scan_timeout)
        if device is None:
            if args.forever:
                continue
            log.error("no monitor found within %.0fs", args.scan_timeout)
            return 1
        try:
            await session(device, store)
        except Exception as exc:  # bleak raises a zoo of backend-specific errors
            log.error("session failed: %s", exc)
            if not args.forever:
                return 1
        if not args.forever:
            return 0


async def cmd_explore(args) -> int:
    """dump services and characteristics, read whatever is readable. no writes."""
    device = await find_device(args.address, args.scan_timeout)
    if device is None:
        log.error("no monitor found")
        return 1
    async with BleakClient(device) as client:
        for service in client.services:
            print(f"service {service.uuid}  {service.description}")
            for char in service.characteristics:
                props = ",".join(char.properties)
                line = f"  char {char.uuid}  [{props}]  {char.description}"
                if "read" in char.properties and not service.uuid.startswith("f000ffc0"):
                    try:
                        value = bytes(await client.read_gatt_char(char))
                        line += f"  = {value.hex()}"
                        if char.uuid == protocol.CLOCK_UUID:
                            when, extra = protocol.parse_clock(value)
                            line += f"  ({when}, byte5 {extra:#04x})"
                        elif all(32 <= b < 127 for b in value) and value:
                            line += f"  ({value.decode()!r})"
                    except Exception as exc:
                        line += f"  (read failed: {exc})"
                print(line)
    return 0


async def _read_firmware(client: BleakClient) -> str:
    try:
        return bytes(await client.read_gatt_char("00002a26-0000-1000-8000-00805f9b34fb")).decode(errors="replace")
    except Exception:
        return ""


async def history_session(device: BLEDevice, store: Store | None, encrypted: bool | None, users: list[int]) -> int:
    """download the readings the monitor still holds for the app. nothing is deleted."""
    answers: asyncio.Queue[bytes] = asyncio.Queue()
    count = 0
    async with BleakClient(device) as client:
        log.info("connected to %s", device.address)
        firmware = await _read_firmware(client)
        raw_counts = bytes(await client.read_gatt_char(protocol.NUM_READINGS_UUID))
        log.debug("num-readings raw %s, firmware %r", raw_counts.hex(), firmware)
        if encrypted is None:
            # the app decides by firmware version; the length of this read tells
            # what the monitor actually does, which is what counts
            encrypted = len(raw_counts) >= 16
            log.info("encryption %s (firmware %s, num-readings answer is %d bytes)",
                     "on" if encrypted else "off", firmware or "?", len(raw_counts))
        counts_payload = protocol.decrypt_block_at(raw_counts, 0) if encrypted and len(raw_counts) >= 16 else raw_counts
        n_users, per_user, pending = protocol.parse_num_readings(counts_payload)
        log.info("%d users, %d slots each, pending readings: %s",
                 n_users, per_user, ", ".join(f"{protocol.USERS[i] if i < 2 else i}={c}" for i, c in enumerate(pending)))

        await client.start_notify(protocol.REQUESTED_READING_UUID, lambda _c, d: answers.put_nowait(bytes(d)))
        for user in users:
            for index in range(pending[user] if user < len(pending) else 0):
                request = protocol.build_reading_request(user, index, encrypted)
                log.debug("request user %d index %d: %s", user, index, request.hex())
                await client.write_gatt_char(protocol.READING_REQUEST_UUID, request, response=True)
                try:
                    payload = await asyncio.wait_for(answers.get(), timeout=5.0)
                except TimeoutError:
                    log.error("no answer for user %d index %d; stopping", user, index)
                    return count
                log.debug("raw %s", payload.hex())
                if encrypted:
                    payload = protocol.decrypt_block_at(payload, 1)
                    log.debug("decrypted %s", payload.hex())
                try:
                    m = protocol.parse_measurement(payload)
                except protocol.ProtocolError as exc:
                    log.warning("%s", exc)
                    continue
                count += 1
                print(m, flush=True)
                if store is not None and not store.add(m, device.address):
                    log.info("already stored")
        await client.stop_notify(protocol.REQUESTED_READING_UUID)
    log.info("downloaded %d reading(s)", count)
    return count


async def cmd_history(args) -> int:
    store = None if args.no_store else Store(args.db)
    device = await find_device(args.address, args.scan_timeout)
    if device is None:
        log.error("no monitor found within %.0fs", args.scan_timeout)
        return 1
    encrypted = {"auto": None, "on": True, "off": False}[args.encryption]
    users = [protocol.USERS.index(args.user)] if args.user else [0, 1]
    await history_session(device, store, encrypted, users)
    return 0


def cmd_list(args) -> int:
    rows = Store(args.db).all(args.user)
    for r in rows:
        flags = f" [{r['flags']}]" if r["flags"] else ""
        print(f"{r['measured_at'] or r['received_at']}  user {r['user'] or '?'}  "
              f"{r['systolic']}/{r['diastolic']}  pulse {r['pulse']}{flags}")
    if not rows:
        print("no measurements stored", file=sys.stderr)
    return 0


def cmd_export(args) -> int:
    store = Store(args.db)
    store.export_csv(store.all(args.user))
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="exactfit", description=__doc__)
    p.add_argument("--db", type=Path, default=DEFAULT_DB, help=f"sqlite file (default {DEFAULT_DB})")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    ble = argparse.ArgumentParser(add_help=False)
    ble.add_argument("-v", "--verbose", action="store_true", help="also accepted here, after the subcommand")
    ble.add_argument("--address", help="bluetooth address; default: first bua6350 seen")
    ble.add_argument("--scan-timeout", type=float, default=60.0)

    s = sub.add_parser("listen", parents=[ble], help="wait for the monitor, record its measurements")
    s.add_argument("--forever", action="store_true", help="keep scanning after each session")
    s.add_argument("--no-store", action="store_true", help="print only")
    s.set_defaults(func=cmd_listen, is_async=True)

    s = sub.add_parser("history", parents=[ble], help="download the readings stored on the monitor (deletes nothing)")
    s.add_argument("--user", choices=protocol.USERS, help="only this user; default both")
    s.add_argument("--encryption", choices=("auto", "on", "off"), default="auto",
                   help="aes-128 wrapping of commands and records; auto detects it from the monitor")
    s.add_argument("--no-store", action="store_true", help="print only")
    s.set_defaults(func=cmd_history, is_async=True)

    s = sub.add_parser("explore", parents=[ble], help="dump the gatt table and readable values")
    s.set_defaults(func=cmd_explore, is_async=True)

    for name, func in (("list", cmd_list), ("export", cmd_export)):
        s = sub.add_parser(name, help=f"{name} stored measurements" + (" as csv" if name == "export" else ""))
        s.add_argument("--user", choices=protocol.USERS)
        s.set_defaults(func=func, is_async=False)

    args = p.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )
    # bleak and dbus-fast log every advertisement in the neighbourhood at debug level
    for noisy in ("bleak", "dbus_fast", "asyncio"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    args.db.parent.mkdir(parents=True, exist_ok=True)
    try:
        return asyncio.run(args.func(args)) if args.is_async else args.func(args)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
