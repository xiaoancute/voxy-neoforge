#!/usr/bin/env python3
import socket
import struct
import sys


def receive_exact(connection, length):
    data = bytearray()
    while len(data) < length:
        chunk = connection.recv(length - len(data))
        if not chunk:
            raise ConnectionError("RCON connection closed")
        data.extend(chunk)
    return bytes(data)


def send_packet(connection, request_id, packet_type, payload):
    body = struct.pack("<ii", request_id, packet_type) + payload.encode("utf-8") + b"\0\0"
    connection.sendall(struct.pack("<i", len(body)) + body)


def receive_packet(connection):
    length = struct.unpack("<i", receive_exact(connection, 4))[0]
    if length < 10 or length > 4_194_304:
        raise ValueError(f"Invalid RCON packet length: {length}")
    body = receive_exact(connection, length)
    request_id, packet_type = struct.unpack("<ii", body[:8])
    return request_id, packet_type, body[8:-2].decode("utf-8", errors="replace")


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: ci_rcon.py HOST PORT PASSWORD COMMAND")
    host, port, password, command = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    with socket.create_connection((host, port), timeout=10) as connection:
        connection.settimeout(10)
        send_packet(connection, 1, 3, password)
        auth_id, _, _ = receive_packet(connection)
        if auth_id == -1:
            raise PermissionError("RCON authentication failed")
        send_packet(connection, 2, 2, command)
        response_id, _, response = receive_packet(connection)
        if response_id != 2:
            raise ValueError(f"Unexpected RCON response id: {response_id}")
        print(response)


if __name__ == "__main__":
    main()
