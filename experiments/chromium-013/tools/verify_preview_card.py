#!/usr/bin/env python3
"""Verify that real Chromium JPEG pixels were actually rendered INSIDE a tab card.

This intentionally has zero pip dependencies. Android's screencap PNG is read
with stdlib PNG filters, and the card rectangle comes from uiautomator XML.
A saved JPEG alone is NOT sufficient: a broken ImageView or CENTER_CROP could
still display a placeholder or a visually empty middle-of-page region.
"""
from __future__ import annotations

import argparse
import re
import struct
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path


def read_rgba_png(path: Path) -> tuple[int, int, bytes, int]:
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Not an Android PNG screenshot")
    offset = 8
    width = height = channels = 0
    payload = bytearray()
    while offset + 12 <= len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        chunk_type = data[offset + 4 : offset + 8]
        chunk = data[offset + 8 : offset + 8 + size]
        if chunk_type == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            if depth != 8 or color not in (2, 6) or interlace:
                raise ValueError(f"Unsupported PNG mode: depth={depth} color={color}")
            channels = 3 if color == 2 else 4
        if chunk_type == b"IDAT":
            payload.extend(chunk)
        offset += size + 12
        if chunk_type == b"IEND":
            break
    if width < 1 or height < 1 or channels == 0:
        raise ValueError("Missing PNG dimensions")
    raster = zlib.decompress(payload)
    stride = width * channels
    pixels = bytearray(stride * height)
    read_at = 0
    previous = bytearray(stride)
    for y in range(height):
        filter_type = raster[read_at]
        read_at += 1
        source = raster[read_at : read_at + stride]
        read_at += stride
        if len(source) != stride:
            raise ValueError("Truncated PNG scanline")
        row = bytearray(stride)
        for i in range(stride):
            a = row[i - channels] if i >= channels else 0
            b = previous[i]
            c = previous[i - channels] if i >= channels else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
                predictor = a
            elif filter_type == 2:
                predictor = b
            elif filter_type == 3:
                predictor = (a + b) // 2
            elif filter_type == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                predictor = a if pa <= pb and pa <= pc else b if pb <= pc else c
            else:
                raise ValueError(f"Unsupported PNG filter: {filter_type}")
            row[i] = (source[i] + predictor) & 255
        pixels[y * stride : (y + 1) * stride] = row
        previous = row
    return width, height, bytes(pixels), channels


def verify(screenshot: Path, hierarchy: Path) -> None:
    width, height, pixels, channels = read_rgba_png(screenshot)
    root = ET.parse(hierarchy).getroot()
    image_nodes = [
        node for node in root.iter("node")
        if node.get("content-desc") == "ページのプレビュー"
    ]
    if len(image_nodes) < 2:
        raise AssertionError(f"Expected home and fixture tab ImageViews, got {len(image_nodes)}")

    # Home is the first tab, the loaded colored Chromium fixture is the last.
    bounds = image_nodes[-1].get("bounds", "")
    values = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not values:
        raise AssertionError("No real tab-preview card bounds in Android hierarchy")
    left, top, right, bottom = (int(v) for v in values.groups())
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        raise AssertionError(f"Preview card outside screenshot: {bounds}")

    orange = green = 0
    # Sample the actual card, not the webpage or any dialog scrim behind it.
    # Ignore card borders and anti-aliased edges.
    for y in range(top + 6, bottom - 6, 2):
        for x in range(left + 6, right - 6, 2):
            offset = (y * width + x) * channels
            r, g, b = pixels[offset : offset + 3]
            if r > 130 and r > g + 35 and g > b + 22:
                orange += 1
            if g > 75 and g > r + 28 and g > b + 12:
                green += 1
    # Substantial page-color blocks inside the tab card prove visual placement.
    if orange < 40 or green < 40:
        raise AssertionError(
            f"Tab ImageView is missing a top-aligned real screenshot: "
            f"orange={orange}, green={green}, card={bounds}"
        )
    print(f"PASS: real Chromium pixels visible in tab card {bounds} "
          f"(orange={orange}, green={green}); image={width}x{height}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("screenshot", type=Path)
    parser.add_argument("hierarchy", type=Path)
    args = parser.parse_args()
    verify(args.screenshot, args.hierarchy)
