#!/usr/bin/env bash
# Renders the feature-tour video: desktop/build/neckscape-features.mp4
# Frames come from the real UI (FeatureVideo test), music from the app's synth,
# and the voice-over from tools/narration.txt via Piper TTS (offline).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME_JPACKAGE:-$(ls -d "$HOME"/.jdks/jdk-21* | tail -1)}"
PIPER="${PIPER:-$HOME/.local/share/piper/venv/bin/piper}"
VOICE="${VOICE:-$HOME/.local/share/piper/voices/en_GB-cori-high.onnx}"
cd "$ROOT"

# Voice each narration line; a clip is only regenerated when its text changes.
N=desktop/build/narration
mkdir -p "$N"
grep -v '^#' tools/narration.txt | grep '|' | while IFS='|' read -r id text; do
    if [[ -f "$N/$id.wav" && "$(cat "$N/$id.txt" 2>/dev/null)" == "$text" ]]; then continue; fi
    printf '%s\n' "$text" | "$PIPER" -m "$VOICE" -f "$N/$id.22k.wav" >/dev/null 2>&1
    ffmpeg -hide_banner -loglevel error -y -i "$N/$id.22k.wav" -ar 44100 -ac 1 -c:a pcm_s16le "$N/$id.wav"
    printf '%s' "$text" > "$N/$id.txt"
    echo "voiced: $id"
done

./gradlew --console=plain -q :desktop:test --tests '*FeatureVideo*' -Pneckscape.video=1 --rerun
V=desktop/build/video
ffmpeg -hide_banner -loglevel error -y \
    -f concat -safe 0 -i "$V/frames.ffconcat" -i "$V/audio.wav" \
    -vf "fps=30,format=yuv420p" -c:v libx264 -preset medium -crf 18 -tune stillimage \
    -c:a aac -b:a 192k -shortest -movflags +faststart \
    desktop/build/neckscape-features.mp4
ffprobe -v error -show_entries format=duration,size -of default=nw=1 desktop/build/neckscape-features.mp4
