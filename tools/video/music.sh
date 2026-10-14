#!/bin/sh
set -e
DUR=${1:-146}
IN=${2:-6.2}
GUITAR=${3:-54.2}
BEAT_OUT=${4:-133.4}
GUITAR_OUT=${5:-137.0}
OUT=${6:-out/bed.wav}
L="/Library/Audio/Apple Loops/Apple/07 Chillwave"
SKEW=$(python3 -c "print(round((2.4 - ($IN % 2.4)) % 2.4, 3))")
DIN=$(python3 -c "print(int($IN * 1000))")
DG=$(python3 -c "print(int($GUITAR * 1000))")
cd "$(dirname "$0")"
mkdir -p "$(dirname "$OUT")"
ffmpeg -nostdin -y -loglevel error \
  -stream_loop -1 -i "$L/Dreamers Synth.caf" -stream_loop -1 -i "$L/Dreamers Bass.caf" \
  -stream_loop -1 -i "$L/Analog Clap Beat 01.caf" -stream_loop -1 -i "$L/Dreamers Guitar.caf" \
  -filter_complex "\
[0]atrim=$SKEW:$(python3 -c "print($SKEW + $DUR)"),asetpts=PTS-STARTPTS,volume=0.85,afade=t=in:st=0:d=2.5[s];\
[1]atrim=0:$(python3 -c "print($DUR - $IN)"),asetpts=PTS-STARTPTS,adelay=$DIN|$DIN,volume=0.9,afade=t=in:st=$IN:d=0.4[b];\
[2]atrim=0:$(python3 -c "print($BEAT_OUT - $IN)"),asetpts=PTS-STARTPTS,adelay=$DIN|$DIN,volume=0.75,afade=t=in:st=$IN:d=0.3,afade=t=out:st=$(python3 -c "print($BEAT_OUT - 0.3)"):d=0.3[d];\
[3]atrim=0:$(python3 -c "print($GUITAR_OUT - $GUITAR)"),asetpts=PTS-STARTPTS,adelay=$DG|$DG,volume=0.6,afade=t=in:st=$GUITAR:d=2,afade=t=out:st=$(python3 -c "print($GUITAR_OUT - 2)"):d=2[g];\
[s][b][d][g]amix=inputs=4:normalize=0,afade=t=out:st=$(python3 -c "print($DUR - 4)"):d=4,atrim=0:$DUR,loudnorm=I=-18:TP=-1.5:LRA=11[a]" \
  -map "[a]" -ar 48000 -c:a pcm_s16le "$OUT"
