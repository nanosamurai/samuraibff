# syntax=docker/dockerfile:1

## --- Builder: compile UI + proto + uberjar ---
FROM clojure:temurin-21-tools-deps AS builder

WORKDIR /app

# Install Node.js (for shadow-cljs UI build)
RUN apt-get update \
  && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
  && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
  && apt-get install -y --no-install-recommends nodejs \
  && rm -rf /var/lib/apt/lists/*

# Install buf (for protobuf Java/gRPC stub generation)
RUN curl -sSL "https://github.com/bufbuild/buf/releases/download/v1.57.2/buf-Linux-x86_64" -o /usr/local/bin/buf \
  && chmod +x /usr/local/bin/buf

# Cache deps before copying whole repo
COPY deps.edn build.clj buf.yaml buf.gen.yaml package.json ./

RUN clojure -P

RUN npm install

# Now bring in the full source tree
COPY . .

# Build UI bundle (serves from resources/public/js)
RUN npm run ui:release

# Generate protobuf stubs and compile them into target/classes
RUN clojure -T:build proto+compile

# Build uberjar
RUN clojure -T:build uber


## --- Runtime: small-ish JRE image ---
FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

# Create non-root user
RUN useradd -r -u 10001 -g root samuraibff

COPY --from=builder /app/target/samuraibff.jar /app/samuraibff.jar

EXPOSE 8000

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

USER 10001

ENTRYPOINT ["java","-jar","/app/samuraibff.jar"]