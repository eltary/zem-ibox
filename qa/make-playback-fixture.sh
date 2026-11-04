#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p qa/generated/fixtures
ffmpeg -y -v error -f lavfi -i testsrc2=size=1920x1080:rate=25 -f lavfi -i sine=frequency=440:sample_rate=8000 -t 60 -c:v libx264 -preset ultrafast -profile:v main -bf 2 -pix_fmt yuv420p -b:v 18M -minrate 18M -maxrate 18M -bufsize 36M -x264-params nal-hrd=cbr:force-cfr=1:keyint=50 -threads 2 -c:a pcm_s16le -ac 1 -f mov qa/generated/fixtures/fullhd.MOV
