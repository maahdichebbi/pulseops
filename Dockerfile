# ── Stage 1: Build ───────────────────────────────────────────────────────────
# Use the official Maven image that bundles JDK 21.
# This stage compiles the code and produces the fat JAR.
# The entire Maven + JDK toolchain stays in this stage — it never reaches production.
FROM maven:3.9-eclipse-temurin-21 AS builder

# Set the working directory inside the container
WORKDIR /app

# Copy only the dependency descriptor first.
# Docker caches each layer — if pom.xml hasn't changed, the dependency
# download layer is reused on the next build even if source code changed.
COPY pom.xml .

# Download all dependencies into the local Maven cache (cached as a layer).
# We use `mvn` — NOT `./mvnw` — because this image already ships Maven.
# mvnw would try to download+unzip Maven itself, but `unzip` isn't in this image.
RUN mvn dependency:go-offline -q

# Now copy the source code and build the fat JAR.
# -DskipTests: integration tests require Docker-in-Docker; run them in CI instead.
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
# Use a minimal JRE-only image — no compiler, no Maven, no source code.
# eclipse-temurin:21-jre is ~220 MB vs ~600 MB for the full JDK image.
FROM eclipse-temurin:21-jre

WORKDIR /app

# Create a non-root Linux user called 'pulseops'.
# If the container is ever compromised, the attacker has no root privileges.
RUN groupadd --system pulseops && useradd --system --gid pulseops pulseops

# Copy only the compiled JAR from the builder stage.
# No source code, no pom.xml, no Maven cache in the final image.
COPY --from=builder /app/target/pulseops-*.jar app.jar

# Change ownership so the non-root user can read the file
RUN chown pulseops:pulseops app.jar

# Switch to the non-root user for all subsequent commands
USER pulseops

# Document which port the app listens on (does not publish it — that's docker-compose's job)
EXPOSE 8080

# Start the app.
# No secrets here — all config comes from environment variables at runtime.
ENTRYPOINT ["java", "-jar", "app.jar"]
