"""Builds the Ultra HDR and motion-photo fixtures from plain JPEGs and an MP4.

Run by make.sh; not needed for the tests. Usage:
  assemble.py ultrahdr PRIMARY GAINMAP OUT
  assemble.py motion PRIMARY VIDEO OUT
"""

import struct
import sys


def segments(jpeg):
    """Splits a JPEG into (header segments, rest from the first DQT on)."""
    assert jpeg[:2] == b"\xff\xd8"
    i = 2
    segs = []
    while True:
        marker = jpeg[i + 1]
        length = struct.unpack(">H", jpeg[i + 2 : i + 4])[0]
        if not (0xE0 <= marker <= 0xEF or marker == 0xFE):
            return segs, jpeg[i:]
        segs.append(jpeg[i : i + 2 + length])
        i += 2 + length


def app1_xmp(xml):
    body = b"http://ns.adobe.com/xap/1.0/\x00" + xml.encode()
    return b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body


def com(text):
    body = text.encode()
    return b"\xff\xfe" + struct.pack(">H", len(body) + 2) + body


XMP_HEAD = (
    '<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">\n'
    '  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
)
XMP_TAIL = "  </rdf:RDF>\n</x:xmpmeta>\n"


def mpf(primary_len, gain_len, gain_offset):
    """An MPF APP2 segment (little endian), two MP entries."""
    entries = struct.pack("<IIIHH", 0x030000, primary_len, 0, 0, 0)
    entries += struct.pack("<IIIHH", 0, gain_len, gain_offset, 0, 0)
    ifd_offset = 8
    count = 3
    data_offset = ifd_offset + 2 + count * 12 + 4
    tiff = b"II*\x00" + struct.pack("<I", ifd_offset)
    tiff += struct.pack("<H", count)
    tiff += struct.pack("<HHI4s", 0xB000, 7, 4, b"0100")
    tiff += struct.pack("<HHII", 0xB001, 4, 1, 2)
    tiff += struct.pack("<HHII", 0xB002, 7, 32, data_offset)
    tiff += struct.pack("<I", 0)
    tiff += entries
    body = b"MPF\x00" + tiff
    return b"\xff\xe2" + struct.pack(">H", len(body) + 2) + body


def ultrahdr(primary, gain):
    gsegs, grest = segments(gain)
    gain_xmp = app1_xmp(
        XMP_HEAD
        + '    <rdf:Description rdf:about="" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"\n'
        '      hdrgm:Version="1.0" hdrgm:GainMapMin="0" hdrgm:GainMapMax="2.5"\n'
        '      hdrgm:Gamma="1" hdrgm:OffsetSDR="0" hdrgm:OffsetHDR="0"\n'
        '      hdrgm:HDRCapacityMin="0" hdrgm:HDRCapacityMax="2.5" hdrgm:BaseRenditionIsHDR="False"/>\n'
        + XMP_TAIL
    )
    gain = b"\xff\xd8" + b"".join(s for s in gsegs if s[1] == 0xE0) + gain_xmp + grest
    psegs, prest = segments(primary)
    xmp = app1_xmp(
        XMP_HEAD
        + '    <rdf:Description rdf:about=""\n'
        '        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"\n'
        '        xmlns:Container="http://ns.google.com/photos/1.0/container/"\n'
        '        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"\n'
        '        xmlns:xmp="http://ns.adobe.com/xap/1.0/"\n'
        '        xmlns:exif="http://ns.adobe.com/exif/1.0/"\n'
        '      hdrgm:Version="1.0"\n'
        '      xmp:CreatorTool="HDR+ 1.0.123456789zd"\n'
        '      exif:GPSLatitude="43,9.8696N">\n'
        "      <Container:Directory>\n"
        "        <rdf:Seq>\n"
        '          <rdf:li rdf:parseType="Resource">\n'
        '            <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>\n'
        "          </rdf:li>\n"
        '          <rdf:li rdf:parseType="Resource">\n'
        f'            <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="{len(gain)}"/>\n'
        "          </rdf:li>\n"
        "        </rdf:Seq>\n"
        "      </Container:Directory>\n"
        "    </rdf:Description>\n" + XMP_TAIL
    )
    exif = [s for s in psegs if s[1] == 0xE1 and s[4:10] == b"Exif\x00\x00"]
    icc = [s for s in psegs if s[1] == 0xE2]
    # A comment after the MPF segment: removing it moves the gain map closer
    # to the MPF header, so its offset has to be recalculated.
    head = [b"\xff\xd8"] + exif + icc + [xmp]
    tail = [com("written after MPF, removed by stripping")]
    mpf_pos = sum(map(len, head))
    probe = mpf(0, 0, 0)
    primary_len = mpf_pos + len(probe) + sum(map(len, tail)) + len(prest)
    gain_offset = primary_len - (mpf_pos + 8)
    return b"".join(head) + mpf(primary_len, len(gain), gain_offset) + b"".join(tail) + prest + gain


def motion(primary, video):
    psegs, prest = segments(primary)
    xmp = app1_xmp(
        XMP_HEAD
        + '    <rdf:Description rdf:about=""\n'
        '        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"\n'
        '        xmlns:Container="http://ns.google.com/photos/1.0/container/"\n'
        '        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"\n'
        '        xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"\n'
        '      GCamera:MotionPhoto="1"\n'
        '      GCamera:MotionPhotoVersion="1"\n'
        '      GCamera:MotionPhotoPresentationTimestampUs="100000"\n'
        '      xmpMM:DocumentID="adobe:docid:photoshop:0123456789abcdef">\n'
        "      <Container:Directory>\n"
        "        <rdf:Seq>\n"
        '          <rdf:li rdf:parseType="Resource">\n'
        '            <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>\n'
        "          </rdf:li>\n"
        '          <rdf:li rdf:parseType="Resource">\n'
        f'            <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="{len(video)}" Item:Padding="0"/>\n'
        "          </rdf:li>\n"
        "        </rdf:Seq>\n"
        "      </Container:Directory>\n"
        "    </rdf:Description>\n" + XMP_TAIL
    )
    keep = [s for s in psegs if s[1] != 0xE1 or s[4:10] == b"Exif\x00\x00"]
    return b"\xff\xd8" + b"".join(keep) + xmp + prest + video


def main():
    kind, a, b, out = sys.argv[1:5]
    first = open(a, "rb").read()
    second = open(b, "rb").read()
    data = ultrahdr(first, second) if kind == "ultrahdr" else motion(first, second)
    open(out, "wb").write(data)


main()
