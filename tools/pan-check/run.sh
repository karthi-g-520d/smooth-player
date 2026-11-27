#!/usr/bin/env bash
# Builds the textured synthetic pan clips (plan §6, C1) and measures the motion search's vector error on them.
# Needs ffmpeg, java and a standalone kotlinc on PATH. Usage: tools/pan-check/run.sh [work dir]
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
work="${1:-$(mktemp -d)}"
mkdir -p "$work"
cd "$work"

# A still, detailed texture twice the frame width: two octaves of smoothed noise, so every block has detail that
# survives the 1/6 and 1/12 motion-luma levels.
ffmpeg -loglevel error -y \
  -f lavfi -i "nullsrc=s=480x135,geq=lum='random(1)*255':cb=128:cr=128,scale=3840:1080:flags=bicubic" \
  -f lavfi -i "nullsrc=s=120x34,geq=lum='random(2)*255':cb=128:cr=128,scale=3840:1080:flags=bicubic" \
  -filter_complex "[0][1]blend=all_mode=average,eq=contrast=1.3" -frames:v 1 texture.png

# Crop moves right by S px a frame, so the content moves left by S. 16 s at 30 fps, 1080p H.264 High.
for speed in 4 16 40; do
  ffmpeg -loglevel error -y -loop 1 -framerate 30 -i texture.png \
    -vf "crop=1920:1080:'mod(n*$speed,1920)':0" -t 16 \
    -c:v libx264 -profile:v high -crf 18 -pix_fmt yuv420p -g 60 "C1_texpan${speed}px.mp4"
done

kotlinc "$repo/engine/impl/src/main/kotlin/com/example/smoothplayer/engine/impl/interp/MotionSearch.kt" \
  "$repo/tools/pan-check/PanCheck.kt" -include-runtime -d pancheck.jar 2>/dev/null

frames=40
for speed in 4 16 40; do
  ffmpeg -loglevel error -y -i "C1_texpan${speed}px.mp4" -frames:v $frames -f rawvideo -pix_fmt rgb24 frames.rgb
  echo "== 1080p, ${speed} px/frame"
  java -cp pancheck.jar com.example.smoothplayer.engine.impl.interp.PanCheckKt \
    frames.rgb 1920 1080 $frames 6 "-$speed" $((1920 / speed))
done
# 720p uses the 1/4 luma. The 16 px clip scaled to 720p moves 10.67 px a frame.
ffmpeg -loglevel error -y -i C1_texpan16px.mp4 -frames:v $frames -vf scale=1280:720:flags=bicubic \
  -f rawvideo -pix_fmt rgb24 frames.rgb
echo "== 720p, 10.67 px/frame"
java -cp pancheck.jar com.example.smoothplayer.engine.impl.interp.PanCheckKt frames.rgb 1280 720 $frames 4 -10.6667 120
rm -f frames.rgb
echo "Clips are in $work"
