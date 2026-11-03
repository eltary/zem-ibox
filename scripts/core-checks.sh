#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p qa/classes
javac -d qa/classes app/src/main/java/io/zem/ibox/{CtpCodec,FrameAssembler,TcpMediaReader,NalUnits,LiveFrameQueue,LiveTiming,Io}.java qa/CoreChecks.java
java -cp qa/classes io.zem.ibox.CoreChecks
