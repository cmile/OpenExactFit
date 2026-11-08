"""aes-128, one block at a time, through the `cryptography` package.

the monitor's firmware (from 1.0.14 on, according to the vendor app) wraps some
payloads in a single aes-128 block under a fixed key: ecb, no iv, no padding.
that is the device's protocol, not a design choice of this tool, which is why
the mode here is ecb. the android app does the same through javax.crypto.
"""

from __future__ import annotations

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def _check(block: bytes, key: bytes) -> None:
    if len(key) != 16:
        raise ValueError("aes-128 needs a 16-byte key")
    if len(block) != 16:
        raise ValueError("aes block must be 16 bytes")


def encrypt_block(block: bytes, key: bytes) -> bytes:
    _check(block, key)
    enc = Cipher(algorithms.AES(key), modes.ECB()).encryptor()  # noqa: S305 - the firmware's format
    return enc.update(block) + enc.finalize()


def decrypt_block(block: bytes, key: bytes) -> bytes:
    _check(block, key)
    dec = Cipher(algorithms.AES(key), modes.ECB()).decryptor()  # noqa: S305 - the firmware's format
    return dec.update(block) + dec.finalize()
