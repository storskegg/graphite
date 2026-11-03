#!/usr/bin/env bash
# Regenerates the synthetic fixtures in this folder. They are committed, so the
# tests do not need this script; it records how they were made. Needs
# ImageMagick, ExifTool, ffmpeg, libheif (heif-enc), libavif (avifenc), cwebp
# and python3, and runs on macOS (it embeds the system's Display P3 profile).
set -euo pipefail
cd "$(dirname "$0")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
icc="/System/Library/ColorSync/Profiles/Display P3.icc"

# Every identifying tag the stripping choices must remove, plus dates and
# exposure settings, which only the strictest choice removes.
tags=(
  -GPSLatitude=43.1645 -GPSLatitudeRef=N -GPSLongitude=89.4308 -GPSLongitudeRef=W
  -GPSAltitude=270 -GPSDateStamp=2020:01:02 -GPSTimeStamp=03:04:05
  -Make=Google "-Model=Pixel 7 Pro" -Software=HDR+1.0 -Artist=Owner
  "-Copyright=Owner Name" -SerialNumber=SN0123 -LensSerialNumber=LSN0123
  "-OwnerName=Owner Name" -ImageUniqueID=0123456789abcdef0123456789abcdef
  "-LensMake=Google" "-LensModel=Pixel 7 Pro back camera 6.81mm f/1.85"
  "-UserComment=a comment" "-ImageDescription=a description"
  "-DateTimeOriginal=2020:01:02 03:04:05" -OffsetTimeOriginal=-06:00
  "-CreateDate=2020:01:02 03:04:05" -OffsetTimeDigitized=-06:00
  "-ModifyDate=2020:01:02 03:04:05" -OffsetTime=-06:00 -SubSecTimeOriginal=123
  -ExposureTime=1/120 -FNumber=1.85 -ISO=100 -FocalLength=6.81 -Flash#=16
  -Orientation#=6 -XResolution=72 -YResolution=72 -ResolutionUnit=inches
)
xmp=(
  "-XMP-dc:Creator=Owner Name" "-XMP-dc:Rights=Owner Name"
  -XMP-xmp:CreatorTool=HDR+1.0 "-XMP-xmp:CreateDate=2020:01:02 03:04:05-06:00"
  -XMP-xmpMM:DocumentID=xmp.did:0123 -XMP-xmpMM:InstanceID=xmp.iid:0123
  -XMP-exif:GPSLatitude=43.1645 -XMP-exif:GPSLongitude=-89.4308
  "-XMP-exif:DateTimeOriginal=2020:01:02 03:04:05-06:00" -XMP-tiff:Make=Google
  -XMP-aux:SerialNumber=SN0123 "-XMP-photoshop:City=Madison"
)
iptc=(-IPTC:By-line=Owner -IPTC:City=Madison "-IPTC:DateCreated=2020:01:02")
et() { exiftool -q -overwrite_original "$@"; }

magick -size 64x48 gradient:red-blue -profile "$icc" -quality 90 "$work/base.jpg"
magick -size 64x48 plasma:fractal -seed 7 -quality 90 "$work/plasma.jpg"
magick -size 16x12 gradient:white-black "$work/thumb.jpg"
magick -size 32x24 gradient:gray-black -quality 80 "$work/gain.jpg"

# JPEG: EXIF with a GPS IFD and an IFD1 thumbnail, XMP, IPTC and a comment.
cp "$work/plasma.jpg" jpeg-full.jpg
et "${tags[@]}" "${xmp[@]}" "${iptc[@]}" "-Comment=a JPEG comment" \
  "-ThumbnailImage<=$work/thumb.jpg" -icc_profile"<=$icc" jpeg-full.jpg

# Ultra HDR: MPF and hdrgm XMP, with a comment after MPF so offsets move.
cp "$work/base.jpg" "$work/primary.jpg"
et "${tags[@]}" "-ThumbnailImage<=$work/thumb.jpg" "$work/primary.jpg"
python3 -I assemble.py ultrahdr "$work/primary.jpg" "$work/gain.jpg" ultrahdr.jpg

# Videos: location, make, model and software in udta and in mdta keys, and
# the creation times in mvhd, tkhd and mdhd.
ffmpeg -v error -y -f lavfi -i testsrc=size=32x24:rate=10:duration=0.3 \
  -f lavfi -i sine=duration=0.3 -c:v libx264 -preset ultrafast -c:a aac -shortest \
  -metadata creation_time=2020-01-02T03:04:05Z \
  -metadata location=+43.1645-089.4308/ -metadata com.android.manufacturer=Google \
  "-metadata" "com.android.model=Pixel 7 Pro" -metadata com.android.capture.fps=30 \
  -metadata com.android.version=14 -movflags +use_metadata_tags "$work/keys.mp4"
ffmpeg -v error -y -i "$work/keys.mp4" -c copy -map_metadata 0 \
  -metadata creation_time=2020-01-02T03:04:05Z -metadata location=+43.1645-089.4308/ -metadata make=Google "-metadata" "model=Pixel 7 Pro" \
  -movflags +use_metadata_tags video.mp4
et -UserData:GPSCoordinates="43.1645, -89.4308" -UserData:Make=Google \
  "-UserData:Model=Pixel 7 Pro" -UserData:Encoder=Lavf video.mp4
ffmpeg -v error -y -i "$work/keys.mp4" -c copy -f mov \
  -metadata location=+43.1645-089.4308/ -metadata creation_time=2020-01-02T03:04:05Z video.mov
et -Keys:GPSCoordinates="43.1645, -89.4308" -Keys:Make=Apple "-Keys:Model=iPhone 14" \
  -Keys:Software=18.0 "-Keys:CreationDate=2020:01:02 03:04:05-06:00" video.mov
ffmpeg -v error -y -i "$work/keys.mp4" -c copy -f 3gp \
  -metadata location=+43.1645-089.4308/ -metadata creation_time=2020-01-02T03:04:05Z video.3gp
ffmpeg -v error -y -f lavfi -i sine=duration=0.3 -c:a aac \
  -metadata creation_time=2020-01-02T03:04:05Z -metadata title=Recording \
  -metadata location=+43.1645-089.4308/ audio.m4a

# Motion photo: the video above, appended after the JPEG.
cp "$work/plasma.jpg" "$work/motion.jpg"
et "${tags[@]}" "$work/motion.jpg"
python3 -I assemble.py motion "$work/motion.jpg" video.mp4 motion.jpg

# PNG: eXIf, XMP in iTXt, tEXt, zTXt and tIME.
magick -size 40x30 gradient:green-yellow -define png:exclude-chunks=date "$work/a.png"
magick "$work/a.png" -set comment "a PNG comment" -set Software "Android" png:- > png.png
et "${tags[@]}" "${xmp[@]}" "-PNG:CreationTime=2020:01:02 03:04:05" png.png

# WebP: VP8X with EXIF, XMP and ICCP.
cwebp -quiet -lossless -metadata icc "$work/base.jpg" -o webp.webp 2>/dev/null ||
  cwebp -quiet -lossless "$work/base.jpg" -o webp.webp
et "${tags[@]}" "${xmp[@]}" "-icc_profile<=$icc" webp.webp

# GIF: two frames, looping, with a comment and XMP.
magick -delay 10 -size 20x16 xc:red xc:blue -loop 0 gif.gif
et "-Comment=a GIF comment" "${xmp[@]}" gif.gif

# HEIC and AVIF: Exif and XMP items.
magick "$work/plasma.jpg" "$work/plasma.png"
heif-enc -q 50 "$work/plasma.png" -o heic.heic >/dev/null
et "${tags[@]}" "${xmp[@]}" heic.heic
avifenc -q 60 -s 10 "$work/plasma.png" avif.avif >/dev/null
et "${tags[@]}" "${xmp[@]}" avif.avif
ls -l
