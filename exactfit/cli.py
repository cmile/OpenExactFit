"""command line: sync with the monitor, list, export and import readings, explore the gatt table."""

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


LIVE_IDLE_TIMEOUT = 300.0   # seconds without a reading before a live session gives up
ANSWER_TIMEOUT = 5.0        # seconds to wait for a requested stored reading


class Session:
    """one connection to the monitor: shared bookkeeping for live and stored readings."""

    def __init__(self, device: BLEDevice, store: Store | None) -> None:
        self.device = device
        self.store = store
        self.count = 0
        self.disconnected = asyncio.Event()

    def on_disconnect(self, _client: BleakClient) -> None:
        self.disconnected.set()

    def handle(self, payload: bytes, source: str) -> None:
        try:
            m = protocol.parse_measurement(payload)
        except protocol.ProtocolError as exc:
            log.warning("%s", exc)
            return
        self.count += 1
        print(f"{m}  ({source})", flush=True)
        if self.store is not None and not self.store.add(m, self.device.address):
            log.info("already stored")


async def _read_firmware(client: BleakClient) -> str:
    try:
        return bytes(await client.read_gatt_char("00002a26-0000-1000-8000-00805f9b34fb")).decode(errors="replace")
    except Exception:
        return ""


async def fetch_history(client: BleakClient, s: Session, encrypted: bool | None, users: list[int]) -> int:
    """download the readings the monitor still holds for the app. nothing is deleted."""
    answers: asyncio.Queue[bytes] = asyncio.Queue()
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

    fetched = 0
    await client.start_notify(protocol.REQUESTED_READING_UUID, lambda _c, d: answers.put_nowait(bytes(d)))
    try:
        for user in users:
            for index in range(pending[user] if user < len(pending) else 0):
                request = protocol.build_reading_request(user, index, encrypted)
                log.debug("request user %d index %d: %s", user, index, request.hex())
                await client.write_gatt_char(protocol.READING_REQUEST_UUID, request, response=True)
                try:
                    payload = await asyncio.wait_for(answers.get(), timeout=ANSWER_TIMEOUT)
                except TimeoutError:
                    log.error("no answer for user %d index %d; stopping", user, index)
                    return fetched
                log.debug("raw %s", payload.hex())
                if encrypted:
                    payload = protocol.decrypt_block_at(payload, 1)
                    log.debug("decrypted %s", payload.hex())
                s.handle(payload, "stored")
                fetched += 1
    finally:
        if not s.disconnected.is_set():
            try:
                await client.stop_notify(protocol.REQUESTED_READING_UUID)
            except Exception:
                pass
    log.info("downloaded %d stored reading(s)", fetched)
    return fetched


async def listen_live(client: BleakClient, s: Session, queue: asyncio.Queue[bytes]) -> int:
    """hand over live readings from ``queue`` until the monitor drops the connection."""
    received = 0
    waiter = asyncio.create_task(s.disconnected.wait())
    try:
        while not s.disconnected.is_set():
            getter = asyncio.create_task(queue.get())
            done, _ = await asyncio.wait({getter, waiter}, timeout=LIVE_IDLE_TIMEOUT,
                                         return_when=asyncio.FIRST_COMPLETED)
            if getter not in done:
                getter.cancel()
                if not done:
                    log.info("no reading for %.0f s, leaving the session", LIVE_IDLE_TIMEOUT)
                break
            payload = getter.result()
            log.debug("raw %s", payload.hex())
            s.handle(payload, "live")
            received += 1
    finally:
        waiter.cancel()
    return received


def _subscribe_live(client: BleakClient) -> tuple[asyncio.Queue[bytes], "asyncio.Future"]:
    queue: asyncio.Queue[bytes] = asyncio.Queue()
    return queue, client.start_notify(protocol.MEASUREMENT_UUID, lambda _c, d: queue.put_nowait(bytes(d)))


async def run_session(device: BLEDevice, store: Store | None, *, history: bool, live: bool,
                      encrypted: bool | None = None, users: list[int] | None = None) -> int:
    """one connection: optionally download stored readings, optionally stay for live ones."""
    s = Session(device, store)
    async with BleakClient(device, disconnected_callback=s.on_disconnect) as client:
        log.info("connected to %s", device.address)
        queue = None
        if live:
            # subscribe before the download so a reading taken meanwhile is not lost
            queue, start = _subscribe_live(client)
            await start
        if history:
            await fetch_history(client, s, encrypted, users or [0, 1])
        if live and queue is not None:
            log.info("waiting for live readings; the monitor ends the session when it switches off")
            await listen_live(client, s, queue)
    log.info("session over, %d reading(s) in total", s.count)
    return s.count


async def _run_with_scan(args, **what) -> int:
    store = None if args.no_store else Store(args.db)
    forever = getattr(args, "forever", False)
    while True:
        device = await find_device(args.address, args.scan_timeout)
        if device is None:
            if forever:
                continue
            log.error("no monitor found within %.0fs", args.scan_timeout)
            return 1
        try:
            await run_session(device, store, **what)
        except Exception as exc:  # bleak raises a zoo of backend-specific errors
            log.error("session failed: %s", exc)
            if not forever:
                return 1
        if not forever:
            return 0


def _encryption(args) -> bool | None:
    return {"auto": None, "on": True, "off": False}[getattr(args, "encryption", "auto")]


def _users(args) -> list[int]:
    user = getattr(args, "user", None)
    return [protocol.USERS.index(user)] if user else [0, 1]


async def cmd_listen(args) -> int:
    return await _run_with_scan(args, history=False, live=True)


async def cmd_history(args) -> int:
    return await _run_with_scan(args, history=True, live=False, encrypted=_encryption(args), users=_users(args))


async def cmd_sync(args) -> int:
    return await _run_with_scan(args, history=True, live=True, encrypted=_encryption(args), users=_users(args))


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


def cmd_list(args) -> int:
    rows = Store(args.db).all(args.user)
    for r in rows:
        flags = f" [{r['flags']}]" if r["flags"] else ""
        note = f"  # {r['note']}" if r["note"] else ""
        print(f"{r['id']:>4}  {r['measured_at'] or r['received_at']}  user {r['user'] or '?'}  "
              f"{r['systolic']}/{r['diastolic']}  pulse {r['pulse']}{flags}{note}")
    if not rows:
        print("no measurements stored", file=sys.stderr)
    return 0


def cmd_export(args) -> int:
    store = Store(args.db)
    store.export_csv(store.all(args.user))
    return 0


def cmd_note(args) -> int:
    """set or clear the owner's note on a reading; the id is the first column of `list`."""
    if not Store(args.db).set_note(args.id, " ".join(args.text)):
        print(f"no reading with id {args.id}", file=sys.stderr)
        return 1
    return 0


def cmd_import(args) -> int:
    """read a csv export (from this tool or the android app) into the database."""
    store = Store(args.db)
    if args.file == "-":
        new, dup, bad = store.import_csv(sys.stdin)
    else:
        with open(args.file, newline="", encoding="utf-8") as f:
            new, dup, bad = store.import_csv(f)
    print(f"{new} new, {dup} already stored, {bad} unreadable")
    return 0 if bad == 0 else 1


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="exactfit", description=__doc__)
    p.add_argument("--db", type=Path, default=DEFAULT_DB, help=f"sqlite file (default {DEFAULT_DB})")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)

    ble = argparse.ArgumentParser(add_help=False)
    ble.add_argument("-v", "--verbose", action="store_true", help="also accepted here, after the subcommand")
    ble.add_argument("--address", help="bluetooth address; default: first bua6350 seen")
    ble.add_argument("--scan-timeout", type=float, default=60.0)
    ble.add_argument("--no-store", action="store_true", help="print only")

    hist = argparse.ArgumentParser(add_help=False)
    hist.add_argument("--user", choices=protocol.USERS, help="only this user's stored readings; default both")
    hist.add_argument("--encryption", choices=("auto", "on", "off"), default="auto",
                      help="aes-128 wrapping of commands and records; auto detects it from the monitor")

    s = sub.add_parser("sync", parents=[ble, hist],
                       help="download stored readings, then stay for live ones until the monitor switches off")
    s.add_argument("--forever", action="store_true", help="scan again after each session")
    s.set_defaults(func=cmd_sync, is_async=True)

    s = sub.add_parser("listen", parents=[ble], help="live readings only, until the monitor switches off")
    s.add_argument("--forever", action="store_true", help="scan again after each session")
    s.set_defaults(func=cmd_listen, is_async=True)

    s = sub.add_parser("history", parents=[ble, hist], help="download stored readings only (deletes nothing)")
    s.set_defaults(func=cmd_history, is_async=True)

    s = sub.add_parser("explore", parents=[ble], help="dump the gatt table and readable values")
    s.set_defaults(func=cmd_explore, is_async=True)

    for name, func in (("list", cmd_list), ("export", cmd_export)):
        s = sub.add_parser(name, help=f"{name} stored measurements" + (" as csv" if name == "export" else ""))
        s.add_argument("--user", choices=protocol.USERS)
        s.set_defaults(func=func, is_async=False)

    s = sub.add_parser("import", help="read a csv export (this tool's or the android app's) into the database")
    s.add_argument("file", help="csv file, or - for stdin")
    s.set_defaults(func=cmd_import, is_async=False)

    s = sub.add_parser("note", help="attach a note to a reading (id from `list`); no text clears it")
    s.add_argument("id", type=int)
    s.add_argument("text", nargs="*")
    s.set_defaults(func=cmd_note, is_async=False)

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
