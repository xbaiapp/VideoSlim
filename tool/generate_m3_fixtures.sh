#!/usr/bin/env bash
set -euo pipefail

out="${1:-/root/artifacts/videoslim/m3/fixtures}"
duration="${M3_FIXTURE_DURATION_SECONDS:-12}"
mkdir -p "$out"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=$duration" \
  -f lavfi -i "aevalsrc=0.15*sin(2*PI*440*t)|0.15*sin(2*PI*660*t):s=48000:d=$duration" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -b:a 128k -shortest \
  "$out/m3-aac-stereo.mp4"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=$duration" \
  -f lavfi -i "sine=frequency=880:sample_rate=44100:duration=$duration" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -b:a 96k -shortest \
  "$out/m3-aac-mono.mp4"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=$duration" \
  -f lavfi -i "sine=frequency=550:sample_rate=48000:duration=$duration" \
  -c:v libvpx-vp9 -deadline realtime -cpu-used 8 \
  -c:a libopus -b:a 96k -shortest \
  "$out/m3-opus.webm"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=30:duration=$duration" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an \
  "$out/m3-no-audio.mp4"

for file in "$out"/m3-*; do
  ffprobe -v error \
    -show_entries format=filename,duration,size \
    -show_entries stream=index,codec_type,codec_name,channels,sample_rate \
    -of compact=p=0:nk=0 "$file"
done
