#!/usr/bin/env bash
# Builds the synthetic Step 8 clips (plan §6.1) that can be made without filming: C1 at 720p for the mid-range bar,
# C5 scrolling text, and C6 hard scene cuts at known times. Each has a silent AAC track, so playback runs on the
# audio clock as real clips do. Needs ffmpeg. Usage: tools/bench/make-clips.sh [out dir]
set -euo pipefail
out="${1:-$(mktemp -d)}"
mkdir -p "$out"
cd "$out"
font=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf
x264=(-c:v libx264 -profile:v high -crf 20 -pix_fmt yuv420p -g 60 -r 30)
audio=(-f lavfi -i "anullsrc=r=48000:cl=stereo")
aac=(-c:a aac -b:a 64k -shortest)

# The C1 texture (see tools/pan-check/run.sh), at 720p: 16 px/frame at 1080p is 10.67 px/frame here.
ffmpeg -loglevel error -y \
  -f lavfi -i "nullsrc=s=480x135,geq=lum='random(1)*255':cb=128:cr=128,scale=3840:1080:flags=bicubic" \
  -f lavfi -i "nullsrc=s=120x34,geq=lum='random(2)*255':cb=128:cr=128,scale=3840:1080:flags=bicubic" \
  -filter_complex "[0][1]blend=all_mode=average,eq=contrast=1.3" -frames:v 1 texture.png
ffmpeg -loglevel error -y -loop 1 -framerate 30 -i texture.png "${audio[@]}" \
  -vf "crop=1920:1080:'mod(n*16,1920)':0,scale=1280:720:flags=bicubic" -t 60 "${x264[@]}" "${aac[@]}" \
  C1_texpan16px_720p.mp4

# C5: credits rolling up 3 px/frame over a dark background, with a ticker crossing the bottom at 8 px/frame.
# Regular text is the aperture problem in its plainest form, so warp artifacts show here first.
python3 - <<'PY'
import random
random.seed(5)
roles = ["Director", "Producer", "Editor", "Camera", "Sound", "Colour", "Gaffer", "Grip", "Music", "Effects"]
names = ["Asha Rao", "Ben Okafor", "Chen Wei", "Dana Ivers", "Elif Kaya", "Femi Adeyemi", "Gita Nair", "Hugo Lind",
         "Ines Duarte", "Jun Sato", "Kofi Mensah", "Lena Vogt", "Mira Patel", "Nils Berg", "Omar Haddad"]
with open("credits.txt", "w") as f:
    for i in range(160):
        f.write(f"{random.choice(roles):>12}    {random.choice(names)}\n")
with open("ticker.txt", "w") as f:
    f.write("   SMOOTHPLAYER STEP 8 TICKER 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ   " * 8)
PY
ffmpeg -loglevel error -y -f lavfi -i "color=c=0x101418:s=1920x1080:r=30" "${audio[@]}" \
  -vf "drawtext=fontfile=$font:textfile=credits.txt:fontsize=44:fontcolor=white:line_spacing=18:x=(w-text_w)/2:y=h-3*n,\
drawbox=x=0:y=980:w=1920:h=100:color=0x203040:t=fill,\
drawtext=fontfile=$font:textfile=ticker.txt:fontsize=56:fontcolor=yellow:x=w-8*n:y=1000" \
  -t 120 "${x264[@]}" "${aac[@]}" C5_credits_1080p.mp4

# C6: 60 s of shots with hard cuts at known times. Each shot moves, so the detector must tell cuts from motion.
# Cut times (s): every boundary in the list below. The summary's scene-cut count should match their number.
shots=(3 5 2 4 3 5 2 4 3 5 2 4 3 5 2 4 4)
sources=(
  "testsrc2=s=1920x1080:r=30"
  "mandelbrot=s=1920x1080:r=30"
  "life=s=1920x1080:r=30:mold=10:ratio=0.1:death_color=#202040:life_color=#e0c060"
  "cellauto=s=1920x1080:r=30:rule=110"
  "gradients=s=1920x1080:r=30:speed=0.05"
  "smptehdbars=s=1920x1080:r=30"
)
: > shots.txt
t=0
for i in "${!shots[@]}"; do
  d=${shots[$i]}
  src=${sources[$((i % ${#sources[@]}))]}
  # Alternate between a texture pan and a generated source, so neighbouring shots never look alike.
  if (( i % 2 == 0 )); then
    speed=$(( (i % 3 + 1) * 6 ))
    ffmpeg -loglevel error -y -loop 1 -framerate 30 -i texture.png \
      -vf "crop=1920:1080:'mod(n*$speed+$((i * 300)),1920)':0" -t "$d" "${x264[@]}" "shot$i.mp4"
  else
    ffmpeg -loglevel error -y -f lavfi -i "$src" -t "$d" "${x264[@]}" "shot$i.mp4"
  fi
  echo "file 'shot$i.mp4'" >> shots.txt
  t=$((t + d))
done
ffmpeg -loglevel error -y -f concat -safe 0 -i shots.txt "${audio[@]}" -t "$t" "${x264[@]}" "${aac[@]}" C6_cuts_1080p.mp4
echo "C6: $(( ${#shots[@]} - 1 )) cuts per pass, ${t} s"
rm -f shot*.mp4 shots.txt credits.txt ticker.txt
ls -la "$out"
