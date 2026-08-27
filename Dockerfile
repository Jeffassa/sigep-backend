# ---- Étape build : compile le jar exécutable (tests ignorés ici, lancés en CI) ----
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /app
# Cache des dépendances : on copie d'abord le pom puis on précharge.
COPY pom.xml .
COPY .mvn/ .mvn/
RUN mvn -B -q dependency:go-offline
COPY src/ src/
RUN mvn -B -q clean package -DskipTests

# ---- Étape runtime : image légère, utilisateur non-root ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# Utilisateur non privilégié
RUN groupadd --system app && useradd --system --gid app app
# Dossier des rapports PDF (monté sur un volume persistant en prod)
RUN mkdir -p /data/rapports && chown -R app:app /data
COPY --from=build /app/target/sigep-backend-*.jar app.jar
USER app
EXPOSE 8080
# egd=urandom : évite l'attente d'entropie au démarrage (warning SecureRandom SHA1PRNG)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
# Démarrage. Le profil et les secrets sont fournis par variables d'environnement.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
