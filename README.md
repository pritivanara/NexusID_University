# NexusID University — Clean Final Package (v52.8 candidate)

## Project overview
NexusID University is a Java 21 / JavaFX desktop application using Maven and MySQL. This package retains the project source, application resources, branding assets, Maven configuration, and database scripts from the supplied v52.8 Global List Theme Fix archive.

## Requirements
- JDK 21
- IntelliJ IDEA with Maven support
- MySQL Server compatible with the project's schema
- A configured local database connection in the project's configuration

## Open and run
1. Extract this ZIP into a new folder.
2. In IntelliJ IDEA, choose **Open** and select the extracted project folder containing `pom.xml`.
3. Wait for Maven to import dependencies.
4. Open the Maven tool window and run **clean**, then **package**.
5. To launch with Maven, run the `javafx:run` goal, or run `com.nexusid.ui.App` using the project's configured JavaFX/Maven setup.
6. If database connection fails, check the local MySQL service and the connection settings in `src/main/java/com/nexusid/config/AppConfig.java` or the database connection class. Do not share passwords publicly.

## Database safety — read before running SQL
- `NexusID_University.sql` is a full schema-creation script. It contains `DROP TABLE IF EXISTS` statements and can delete existing tables and data in the `nexusid_university` database.
- **Do not run `NexusID_University.sql` against your existing project database.** Use it only for a fresh disposable database after making a backup, or create a separate test database first.
- The file in `database/migrations/` is a migration script. Back up the database and review the migration before executing it.
- This package does not run SQL automatically and does not reset your database.

## Theme and scrolling
This candidate carries the supplied shared list/scroll-pane theme changes. Verify both Light and Dark modes in the modules you use, especially User Management and Academic Management. Confirm list backgrounds, row text, selection highlights, dropdowns, sidebar scrolling, and main-content scrolling on your machine.

## Verification status
The ZIP was repackaged and its contents checked. A full Maven build and live JavaFX runtime test have **not** been performed as part of this cleanup. Treat this as a candidate until you run the build and visually verify the app.

## Included
- `pom.xml` — Maven project and dependencies
- `src/` — Java source and application resources
- `NexusID_University.sql` — full schema script (destructive; see Database safety)
- `database/migrations/` — database migration file(s)

Historical version handoff notes and duplicate version-specific README files were removed to keep the package tidy. The project source and runtime assets were retained.
