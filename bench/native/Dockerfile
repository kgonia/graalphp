# The native-image build image: the Truffle PHP image plus what native-image links against (zlib). Build:
#   docker build -t gate1-truffle-native:8.5-graal25.4 bench/native
#   (gate1-truffle-native:8.5 is the same on GraalVM 25.0.4: --build-arg BASE=gate1-truffle:8.5)
ARG BASE=gate1-truffle:8.5-graal25.4
FROM ${BASE}
RUN apt-get update && apt-get install -y --no-install-recommends zlib1g-dev && rm -rf /var/lib/apt/lists/*
