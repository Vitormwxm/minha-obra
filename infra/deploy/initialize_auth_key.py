#!/usr/bin/env python3
"""Initialize one persistent authentication key, without revealing private config."""
import argparse
import base64
import binascii
import hashlib
import hmac
import os
from pathlib import Path
import secrets
import stat

KEY = b"APP_TOTP_ENCRYPTION_KEY"


def check_metadata(fd: int) -> os.stat_result:
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise ValueError("A configuração deve ser arquivo regular, sem aliases.")
    if stat.S_IMODE(info.st_mode) not in (0o600, 0o400):
        raise ValueError("A configuração privada exige modo 600 ou 400.")
    if os.geteuid() not in (0, info.st_uid):
        raise ValueError("Execute como proprietário da configuração privada.")
    return info


def read_key(content: bytes) -> bytes | None:
    values = []
    for line in content.splitlines():
        entry = line.strip()
        if entry.startswith(b"export "):
            entry = entry[7:].lstrip()
        name, separator, value = entry.partition(b"=")
        if separator and name.strip() == KEY:
            values.append(value.strip())
    if not values:
        return None
    if len(values) != 1:
        raise ValueError("Configuração contém mais de uma chave de autenticação.")
    value = values[0]
    if len(value) >= 2 and value[:1] == value[-1:] and value[:1] in (b"'", b'"'):
        value = value[1:-1]
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("Chave existente inválida; ela não foi alterada.") from None
    if len(decoded) != 32:
        raise ValueError("A chave existente precisa representar 32 bytes.")
    return decoded


def has_valid_key(content: bytes) -> bool:
    return read_key(content) is not None


def read_marker(path: Path, owner: os.stat_result) -> bytes | None:
    try:
        marker = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return None
    try:
        info = check_metadata(marker)
        if info.st_uid != owner.st_uid:
            raise ValueError("Marcador privado precisa pertencer ao proprietário da configuração.")
        content = os.read(marker, 128).strip()
        if len(content) != 64 or any(c not in b"0123456789abcdef" for c in content):
            raise ValueError("Marcador da chave de autenticação inválido.")
        return content
    finally:
        os.close(marker)


def save_marker(path: Path, key: bytes, owner: os.stat_result) -> None:
    marker = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        if os.geteuid() == 0:
            os.fchown(marker, owner.st_uid, owner.st_gid)
        os.fchmod(marker, 0o600)
        os.write(marker, hashlib.sha256(key).hexdigest().encode("ascii") + b"\n")
        os.fsync(marker)
    finally:
        os.close(marker)


def initialize(path: Path) -> bool:
    # This helper runs under the deploy lock; the inode lock also protects
    # manual invocations. Do not copy or print other entries in the .env.
    import fcntl

    path = path.absolute()
    if path.resolve(strict=True) != path:
        raise ValueError("Caminho da configuração não pode usar symlinks.")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        info = check_metadata(fd)
        chunks = []
        while chunk := os.read(fd, 8192):
            chunks.append(chunk)
            if sum(map(len, chunks)) > 65536:
                raise ValueError("Arquivo de configuração maior que o esperado.")
        content = b"".join(chunks)
        marker_path = path.with_name(".auth-key.sha256")
        marker = read_marker(marker_path, info)
        existing_key = read_key(content)
        if marker is not None:
            if existing_key is None or not hmac.compare_digest(marker, hashlib.sha256(existing_key).hexdigest().encode("ascii")):
                raise ValueError("A chave de autenticação foi removida ou alterada após a inicialização. Restaure o backup privado.")
        if existing_key is not None:
            if marker is None:
                save_marker(marker_path, existing_key, info)
            return False
        if stat.S_IMODE(info.st_mode) != 0o600:
            raise ValueError("Use chmod 600 para inicializar a nova chave.")
        writer = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW)
        try:
            current = check_metadata(writer)
            if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
                raise ValueError("Configuração mudou durante a inicialização.")
            prefix = b"\n" if content and not content.endswith(b"\n") else b""
            new_key = secrets.token_bytes(32)
            addition = prefix + KEY + b"=" + base64.b64encode(new_key) + b"\n"
            while addition:
                written = os.write(writer, addition)
                addition = addition[written:]
            os.fsync(writer)
            save_marker(marker_path, new_key, info)
        finally:
            os.close(writer)
        return True
    finally:
        os.close(fd)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("env_file", type=Path)
    args = parser.parse_args()
    try:
        created = initialize(args.env_file)
    except ValueError as error:
        parser.exit(1, str(error) + " Nenhum valor privado foi exibido.\n")
    except OSError:
        parser.exit(1, "Não foi possível validar/inicializar a chave privada de autenticação. Nenhum valor foi exibido.\n")
    print("Chave de autenticação inicializada." if created else "Chave de autenticação existente preservada.")


if __name__ == "__main__":
    main()
