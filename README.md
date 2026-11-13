# Answer Script App

The app is built one phase at a time. This README currently covers Phase 1.

## Phase 1: App skeleton, PostgreSQL connection, and health page

### Install on macOS

Install Homebrew if it is not already installed:

```sh
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Install Java 25, PostgreSQL 18, and Maven:

```sh
brew install openjdk@25 postgresql@18 maven
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix postgresql@18)/bin:$PATH"
java -version
mvn -version
```

### Prepare the local database

Start PostgreSQL. Create the app role and database once; `createuser` asks for a password. If the role already exists, use `psql -d postgres` and enter `\password answer_app` at the `psql` prompt to set or change its password. If the database already exists, skip `createdb`.

```sh
brew services start postgresql@18
createuser --pwprompt answer_app
createdb --owner=answer_app answer_scripts
```

In the same Terminal window, set the connection values. Enter the password you chose when prompted:

```sh
export DB_URL=jdbc:postgresql://localhost:5432/answer_scripts
export DB_USERNAME=answer_app
read -s DB_PASSWORD
export DB_PASSWORD
echo
```

### Run

From the project folder:

```sh
mvn spring-boot:run
```

The first run downloads the Maven dependencies. Keep this Terminal window open while using the app. Press `Control-C` to stop it.

### Check

Open [http://localhost:8080/](http://localhost:8080/) in a browser. The page should show `UP` for both **Application** and **PostgreSQL**. The raw health response is at [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health).

## Phase 1 boundaries

Built: Spring Boot app skeleton, PostgreSQL connection, and browser health page.

Not built: login or user accounts, answer-script or question-paper uploads, image or PDF handling, local file storage, and any image extraction. No database tables exist yet, so there is no Flyway migration in this phase.
