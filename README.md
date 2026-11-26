# Answer Script App

The app is built one phase at a time. Phases 1 through 23 are implemented.

## Requirements on macOS

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

## One-time local database setup

Start PostgreSQL and create the app role and database. `createuser` asks for the database password:

```sh
brew services start postgresql@18
createuser --pwprompt answer_app
createdb --owner=answer_app answer_scripts
```

If `answer_app` already exists, set or change its password with `psql -d postgres`, then enter `\password answer_app` and `\q` at the `psql` prompt. If `answer_scripts` already exists, skip `createdb`.

## Run

In each new Terminal window, set the database password and the three demo passwords before the first app startup. If the users table is empty, the app creates whichever of the accounts `scanner`, `paper_uploader`, and `super_admin` have a supplied password, storing BCrypt hashes. This one-time bootstrap is for development; restarting with existing users does not change passwords or create duplicates. The `paper_uploader` account now has the `EXAM_CONTROLLER` role and keeps the existing question-paper page. Flyway applies database migrations at startup.

```sh
export JAVA_HOME="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix postgresql@18)/bin:$PATH"
export DB_URL=jdbc:postgresql://localhost:5432/answer_scripts
export DB_USERNAME=answer_app
read -s DB_PASSWORD
export DB_PASSWORD
echo
read -s DEMO_SCANNER_PASSWORD
export DEMO_SCANNER_PASSWORD
echo
read -s DEMO_UPLOADER_PASSWORD
export DEMO_UPLOADER_PASSWORD
echo
read -s DEMO_SUPER_ADMIN_PASSWORD
export DEMO_SUPER_ADMIN_PASSWORD
echo
mvn spring-boot:run
```

Keep this Terminal window open while using the app. Press `Control-C` to stop it.

## Check Phase 1

Open [http://localhost:8080/](http://localhost:8080/) in a browser. The health page should show `UP` for both **Application** and **PostgreSQL**. The raw health response is at [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health).

## Check Phase 2

On the health page, select **Scanner sign in**.

- Enter username `scanner` and a wrong password. The login form should reject it and show an error.
- Enter username `scanner` and the password you set in `DEMO_SCANNER_PASSWORD`. You should land on the scanner upload page.

## Check Phase 3

On the scanner page, add one JPEG or PNG image up to 15 MB and select **Submit all pages**. The page should show the new submission ID.

From the project folder, check that a server-named image was saved:

```sh
find storage -maxdepth 1 -type f -print
```

In another Terminal window, enter the `answer_app` database password and query the submission/page rows:

```sh
read -s PGPASSWORD
export PGPASSWORD
echo
psql -h localhost -U answer_app -d answer_scripts -c "SELECT s.id AS submission_id, p.id AS page_id, p.stored_filename, p.content_type, p.uploaded_at FROM answer_submissions s JOIN answer_pages p ON p.submission_id = s.id ORDER BY p.uploaded_at DESC LIMIT 5;"
unset PGPASSWORD
```

The `stored_filename` value should match a file in `storage/`. A one-image submission has page number 1.

## Check Phase 4

On the scanner page, choose and **Add page** one image at a time. **Insert at page number** defaults to the next number, so the default adds to the end. To add a forgotten page in the middle, set that field to `2` before selecting **Add page**. The new image appears beside `Page 2`, and the previous pages 2 and later shift down. Check the thumbnail list and final numbers before selecting **Submit all pages**. The browser sends those final numbers in the manifest, and the server stores them; it does not read page numbers from image content.

For a quick insertion check, add three images in order, then add a fourth image with **Insert at page number** set to `2`. Before submission, confirm the newly added thumbnail is beside `Page 2` and the original second and third thumbnails now show `Page 3` and `Page 4`. Submit, then query the returned submission ID:

```sh
psql -h localhost -U answer_app -d answer_scripts -c "SELECT page_number, stored_filename FROM answer_pages WHERE submission_id = 'PASTE_SUBMISSION_ID_HERE' ORDER BY page_number;"
```

The result should list `1`, `2`, `3`, `4` in order.

To check an out-of-order request, use three JPEG/PNG files and pass them to the helper in page 3, page 1, page 2 order:

```sh
python3 scripts/check_page_order.py page-3.png page-1.png page-2.png
```

The helper signs in as `scanner`, sends the pages in order `3, 1, 2`, and prints the submission ID. It prompts for the scanner password unless `DEMO_SCANNER_PASSWORD` is set in that Terminal window. Query that submission with the ID it printed:

```sh
psql -h localhost -U answer_app -d answer_scripts -c "SELECT page_number, stored_filename FROM answer_pages WHERE submission_id = 'PASTE_SUBMISSION_ID_HERE' ORDER BY page_number;"
```

The result should list page numbers `1`, `2`, `3`. File names in `storage/` are generated by the server and are not used for ordering.

## Phase 4 boundaries

Built: browser-side page numbering with insertion and renumbering before submission, a small image preview beside each page number, explicit final page numbers stored for each image, and a uniqueness constraint per submission.

Not built: listing or fetching uploaded images through the app, page retake/delete, reading page numbers or other metadata from images, question-paper upload/login, image conversion, or cloud storage.

## Check Phase 5

Keep the app running as described in **Run**. Connect your phone to the same non-guest Wi-Fi network as the Mac. On macOS, get the Wi-Fi IP with:

```sh
ipconfig getifaddr en0
```

Replace `MAC-IP` in `http://MAC-IP:8080/` with the address printed by that command, without angle brackets. For example, if it prints `192.168.1.20`, open `http://192.168.1.20:8080/` on the phone. A USB cable alone does not forward the Mac's web server to the iPhone.

Sign in as `scanner`. Set **Insert at page number** to `1`, tap **Capture page with camera**, and take the first page. Capture two more pages, leaving the position at the next number each time. The queue should show three thumbnails numbered `Page 1`, `Page 2`, and `Page 3`. Select **Submit all pages**.

Use the submission ID shown on the phone to check the database from the project folder:

```sh
psql -h localhost -U answer_app -d answer_scripts -c "SELECT page_number, stored_filename FROM answer_pages WHERE submission_id = 'PASTE_SUBMISSION_ID_HERE' ORDER BY page_number;"
```

The result should list page numbers `1`, `2`, `3`. If the phone browser does not open the camera directly, its file chooser can still select an image; camera capture depends on browser support for the standard capture input.

If upload fails, the page displays the server message and HTTP status. Check the app terminal for the corresponding request error; that status and message identify the cause.

The app listens on all Mac network interfaces at port `8080`. Confirm that with `lsof -nP -iTCP:8080 -sTCP:LISTEN`; the listener should show `*:8080`. If the phone still cannot connect, check that macOS Firewall allows incoming connections for Java and that the Wi-Fi router does not isolate devices on the network.

## Phase 5 boundaries

Built: phone camera capture into the existing queue, with the same explicit numbering, insertion, preview, and upload behavior as Phase 4.

Not built in Phase 5: camera-based image processing, page retake/delete, question-paper upload/login, image conversion, or cloud storage.

## Check Phase 6

Sign in as `scanner`, open **View submitted scripts** from the upload page, and confirm your submissions appear newest first with each script's pages numbered in ascending order. If the list is empty, submit an image from the Mac upload page first. Select each thumbnail to open its full image. The page-image API and its page-preview data route require the scanner role and only return submissions owned by that scanner. Phase 22 later adds the shared, role-scoped submissions list.

## Phase 6 boundaries

Built: list the signed-in scanner's submissions and their pages in saved order, then fetch each stored image through the storage interface.

Not built in Phase 6: page retake/delete, access to another scanner's submissions, image extraction or processing, question-paper upload/login, image conversion, or cloud storage.

## Check Phase 7

Open the health page and select **Exam Controller sign in**. Log in with username `paper_uploader` and the password set in `DEMO_UPLOADER_PASSWORD`. Select a PDF up to 15 MB and choose **Upload PDF**. The page should show a paper ID.

From the project folder, confirm the server-named PDF exists and the database row was recorded:

```sh
find storage -maxdepth 1 -type f -name '*.pdf' -print
psql -h localhost -U answer_app -d answer_scripts -c "SELECT id, stored_filename, content_type, uploaded_at FROM question_papers ORDER BY uploaded_at DESC LIMIT 5;"
```

New uploads retain a sanitized basename for display in later selection lists; the PDF itself still uses a server-generated stored name. Older rows display the generated name because their original names were not recorded.

In a separate browser session, sign in as `scanner` and open `/question-paper.html`. The scanner should receive **403 Forbidden**. A scanner must not be able to upload a question paper.

## Phase 7 boundaries

Built: a separate seeded uploader account, PDF signature and size validation, server-named local storage, and a database record for each uploaded paper.

Not built: question-paper listing or fetching in the app, PDF extraction or processing, uploader account management, image conversion, or cloud storage.

## Check Phase 8

The model catalog is `src/main/resources/models.json`. The app checks whether each provider key is present in the environment; it never displays or stores the key. To make a model selectable, complete the **Run** setup above, then set that provider's key in the same Terminal window before `mvn spring-boot:run`. For example, add these lines after the demo passwords and before starting the app to enable OpenAI:

```sh
read -s OPENAI_API_KEY
export OPENAI_API_KEY
echo
mvn spring-boot:run
```

Use `GEMINI_API_KEY` for Google or `ANTHROPIC_API_KEY` for Anthropic instead. You do not need to install an additional package or provide a key to run the settings page; without a key, that model is marked unavailable. Keep API keys in the Terminal environment and do not paste them into the app or commit them.

1. Open [http://localhost:8080/](http://localhost:8080/) and select **Super Admin sign in**. Log in with username `super_admin` and the password set in `DEMO_SUPER_ADMIN_PASSWORD`.
2. Select any model marked **Present**, then save. The page should show the saved selection.
3. Stop the app with `Control-C`, start it again with the same environment variables, sign in as `super_admin`, and confirm the same model remains selected.
4. In a separate private browser window, sign in as `scanner` and open [http://localhost:8080/settings.html](http://localhost:8080/settings.html). The request should be refused with **403 Forbidden**.
5. Remove one provider key from the environment and restart the app. That model should show **Missing — unavailable** and its option should be disabled. For example, run `unset OPENAI_API_KEY` before `mvn spring-boot:run` to check the OpenAI entry.

## Phase 8 boundaries

Built: a seeded Super Admin login, a role-protected model settings page, a file-based model catalog, environment-variable key-presence checks, and a database-persisted model selection.

Not built in Phase 8: sending pages to a model, transcription, submission review pages for the Super Admin, OCR or metadata extraction, question-paper reading, model comparison or scoring, cloud storage, or image conversion.

## Check Phase 9

The instructions sent with each request are editable in `config/transcription-instructions.txt`. Changes to this file are read for the next **Read pages** request. No extra software needs to be installed. Use the API key for the provider selected in **Model settings**, entered in the same Terminal window before starting the app. For example, for an OpenAI selection:

```sh
read -s OPENAI_API_KEY
export OPENAI_API_KEY
echo
mvn spring-boot:run
```

Use `GEMINI_API_KEY` for Google or `ANTHROPIC_API_KEY` for Anthropic. These are environment variables; the key is not put in the project files. Complete the database and demo-password setup from **Run** above in this Terminal window as well.

1. Use a real 3–5 page script already submitted through Module 1. Open `http://localhost:8080/`, sign in using `super_admin`, and open **Read submitted scripts** from Model settings.
2. Select the submission. Confirm its pages appear in saved page-number order, then select **Read pages**. The transcription should appear beside the page images, grouped by the question numbers returned by the model.
3. With a model selected, replace its key with a wrong value in the Terminal environment and restart the app. For an OpenAI selection, for example, run `export OPENAI_API_KEY=not-a-valid-key` before restarting. Select **Read pages** again. The page should show a clear provider error, and **Read pages** should be available to retry.

Each request sends every page in page-number order as a JPEG resized to a longest side of at most 1600 pixels. Runs are stored in `transcription_runs`. To inspect the attempts for a submission:

```sh
psql -h localhost -U answer_app -d answer_scripts -c "SELECT provider, model_name, started_at, succeeded, raw_reply, parsed_answers, error_message FROM transcription_runs WHERE submission_id = 'PASTE_SUBMISSION_ID_HERE' ORDER BY started_at DESC;"
```

## Phase 9 boundaries

Built: Super Admin submission list, ordered page review, one-request image transcription using the saved model, editable instructions, stored successful and failed runs, and a side-by-side result view.

Not built in Phase 9: a list of older runs, model switching or comparisons, automatic reading after upload, roll-number or other metadata extraction, marking, question-paper reading, cloud storage, or image conversion.

## Check Phase 10

Set keys for both models you plan to use in the same Terminal environment before starting the app. No additional package is needed. For example, to use OpenAI and Google:

```sh
read -s OPENAI_API_KEY
export OPENAI_API_KEY
echo
read -s GEMINI_API_KEY
export GEMINI_API_KEY
echo
mvn spring-boot:run
```

1. Sign in as `super_admin`, open **Read submitted scripts**, select the same submission used in Phase 9, and press **Read pages** with model A selected. Confirm the result identifies model A.
2. Open **Model settings**, select model B, save, return to the same submission, and press **Read pages** again.
3. Confirm the latest result shows model B. Under **Previous readings**, open model A's earlier result and confirm it appears by itself.

## Phase 10 boundaries

Built: a newest-first list of saved runs for each submission; the latest result opens by default, and an older result can be opened by itself.

Not built in Phase 10: side-by-side result comparisons, scores, rankings, or Phase 11 features.

## Check Phase 11

No new software or API key is needed for this phase. Flyway adds the display filename column when the app starts.

1. Sign in as `super_admin` and open **Read submitted scripts**. Select a submission that has no successful reading. Its **Mark answers** section should say **Read the pages first** and show no marking button.
2. Select a submission with a successful reading, and confirm a question paper has been uploaded by the Exam Controller. The section should show the latest successful reading and a dropdown listing papers by file name and upload time.
3. In a separate private browser session, sign in as `scanner` and open `http://localhost:8080/transcription.html`. Access should be refused with **403 Forbidden**.
4. Selecting a paper only chooses it in the current page. Phase 11 does not send it or the reading to a model.

## Phase 11 boundaries

Built: a Super Admin-only marking-input section showing the latest successful reading and a per-visit question-paper selector.

Not built in Phase 11: a **Mark answers** action, PDF-to-image conversion, model requests for marking, saved marking results, rubrics, mark totals, or Phase 12 features.

## Check Phase 12

Maven downloads Apache PDFBox 3.0.8 from the dependency declared in `pom.xml`; no separate Mac installation is needed. Build or start the app with Maven:

```sh
mvn -U spring-boot:run
```

Use a model with an image-reading capability and its API key in the Terminal environment, as shown in **Check Phase 9**. Use an existing submission with a successful reading from Module 2 and a sample question paper PDF uploaded by the Exam Controller.

1. Sign in as `super_admin` and open **Read submitted scripts**. Select the submission and choose the sample question paper from the dropdown.
2. Press **Mark answers**. The result table should list the questions, maximum marks, marks awarded, and explanations. Unanswered questions should show 0.
3. Confirm no row is above its maximum and the displayed total equals the sum of the rows. The app computes the total from the saved rows.
4. Restart with a wrong API key for the selected provider, then mark again. The page should show a clear error and keep **Mark answers** available for retry.
5. In a separate browser session, sign in as `scanner` and try the marking page or `/api/marking/submissions/PASTE_SUBMISSION_ID_HERE/inputs`. Access should be refused with **403 Forbidden**.

Marking attempts, including failures, are stored in `marking_runs`. To inspect a submission's attempts:

```sh
psql -h localhost -U answer_app -d answer_scripts -c "SELECT provider, model_name, started_at, succeeded, reading_run_id, question_paper_id, raw_reply, parsed_marks, unassigned, error_message FROM marking_runs WHERE submission_id = 'PASTE_SUBMISSION_ID_HERE' ORDER BY started_at DESC;"
```

## Phase 12 boundaries

Built: Super Admin marking with the latest successful reading and a selected question paper, PDFBox rendering of paper pages as JPEG images capped at 1600 pixels on the longest side, one model request with those images and the stored answers, saved successful and failed runs, per-question results, unassigned answers, and an app-computed total.

Not built in Phase 12: rubrics, the answer-any-four rule, final total against the paper maximum, faculty review or mark changes, reports, dashboards, exports, or Phase 13's older marking-run list and model-switch repeat workflow.

## Check Phase 13

Set API keys for both image-capable models you plan to use in the Terminal environment, following the pattern in **Check Phase 10**. No new package or key type is needed.

1. Sign in as `super_admin`, open **Read submitted scripts**, select a submission that already has a successful reading, choose its question paper, and press **Mark answers** with model A selected.
2. Open **Model settings**, select model B, and save. Return to the same submission, select its question paper again, and press **Mark answers**.
3. Confirm the latest result shows model B's name and time. Under **Previous marking runs**, open model A's earlier result. It should appear by itself; the two result tables are not shown together.

Every attempt remains in `marking_runs`; Phase 13 adds only the newest-first run list and an endpoint to open one saved result. No database migration is needed.

## Phase 13 boundaries

Built: each marking attempt remains saved with its model and time, the latest result opens by default, and older runs appear in a newest-first list and open one at a time.

Not built in Phase 13: side-by-side result views, scores, model ranking, rubrics, faculty review, or mark changes.

## Check Phase 14

No API key or additional package is needed. Start PostgreSQL, then run the app using the **Run** setup above. Flyway creates the per-question-paper rubric table on startup. Sign in as `super_admin` and open **Rubrics** from **Read and mark submitted scripts**. At least one question paper must already be uploaded.

1. Choose a paper and paste a rubric where one question's criteria do not add up to its stated maximum, for example:

   ```text
   Q1a (1 mark)
   - Defines the term: 0.5
   ```

   Save. The warning should name `Q1a`, but the text remains saved.
2. Change the criterion value to `1` and save. The total-marks warning should disappear.
3. Reload the page and select the same paper. The saved text should still appear, with its question number listed under **Question numbers covered**.
4. In a separate browser session, sign in as `scanner` and open `http://localhost:8080/rubric.html`. Access should be refused with **403 Forbidden**.

The rubric is stored as the latest exact text for each question paper in `question_paper_rubrics`. Warnings are recomputed from that saved text when the page loads.

## Phase 14 boundaries

Built: a Super Admin-only rubric page, per-paper rubric text replacement, warnings for unreadable lines, duplicate question numbers, and mismatched criterion totals, plus the saved question-number list.

Not built in Phase 14: AI-written rubrics, rubric versions or history, sending a rubric to a model, marking with a rubric, or Phase 15 features.

## Check Phase 15

No additional software is needed. Start PostgreSQL and run the app using the **Run** setup above. Flyway applies the marking-run metadata migration automatically. Marking needs an image-capable model selected in **Model settings** and that provider's API key set in the same Terminal window before `mvn spring-boot:run`, as shown in **Check Phase 9**.

1. Sign in as `super_admin`, open **Read submitted scripts**, and select a submission that already has a successful reading. Choose a question paper with a saved rubric, then press **Mark with rubric**. Every paper question should have a result row, and explanations should name rubric criteria.
2. Open **Rubrics**, remove one question from that paper's rubric, save, and mark the same submission again. The missing question's explanation should start with **No rubric:**, and the notice above the result table should list that question.
3. Edit and save the paper's rubric again. Open the earlier successful rubric marking run under **Previous marking runs**. Expand **Rubric text used in this run** and confirm it still shows the original text from that run.
4. Restart the app with an invalid API key for the selected provider and mark again. The page should show a clear provider error, display no completed marks, and leave **Mark with rubric** available to retry.

The `marking_runs` table stores `run_type` and the exact `rubric_text` used for rubric runs. Existing rows are migrated as `no-rubric`; the earlier **Mark answers** action remains separate and unchanged.

## Phase 15 boundaries

Built: a separate rubric-based marking action that sends the saved rubric, student answers, and question-paper page images together; a per-run exact rubric copy; missing-rubric notices; and the existing computed-total result table.

Not built in Phase 15: model switching and marking again from Phase 16, rubric generation or version history, faculty review or mark changes, model comparisons or rankings, or later phases.

## Check Phase 16

Use a submission with a successful reading, a question paper with a saved rubric, and two image-capable models available in **Model settings**. Set the API keys for both providers in the Terminal environment before starting the app, following **Check Phase 9**. At least one earlier no-rubric marking run from Module 3 should exist for the same submission.

1. Sign in as `super_admin`, open **Read submitted scripts**, select the submission and paper, then press **Mark with rubric** using model A.
2. Open **Model settings**, choose model B and save. Return to the same submission, choose the paper again, and press **Mark with rubric**.
3. Confirm the latest result names model B. Under **Previous marking runs**, open model A's older run; it should replace the current result by itself. Its history entry shows **Rubric**, model, and time.
4. Open an older Module 3 run from the same **Previous marking runs** list. Its entry should show **No rubric**, model, and time, and its result should replace the current one by itself.

The history uses the `run_type` already saved with each marking run; Phase 16 needs no database migration.

## Phase 16 boundaries

Built: one newest-first history list for rubric and no-rubric runs, with each entry labeled by run kind, model, and time; the latest result remains the default and older results open one at a time.

Not built in Phase 16: side-by-side results, scores or model rankings, rubric editing history, faculty review or mark changes, or Phase 17 and later features.

## Check Phase 17

Set all three demo password environment variables before the first startup against an empty database. To check the one-time bootstrap without affecting your existing data, create a fresh local database and point the app to it:

```sh
createdb --owner=answer_app answer_scripts_phase17
```

In the **Run** environment block, use `export DB_URL=jdbc:postgresql://localhost:5432/answer_scripts_phase17` in place of its default database URL line. Then start the app. Flyway migrates the old Spring Security account tables and keeps existing usernames referenced by submissions.

1. On the fresh database, sign in as `scanner`, `paper_uploader`, and `super_admin` with their corresponding `DEMO_*_PASSWORD` values. Each should reach the same page as before; `paper_uploader` is now the **EXAM_CONTROLLER** role and reaches the existing question-paper page. Startup logs warn that the accounts are development accounts.
2. Restart the app with the same database. Confirm the users are not duplicated and their passwords remain unchanged:

   ```sh
   psql -h localhost -U answer_app -d answer_scripts_phase17 -c "SELECT id, username, display_name, role, enabled, must_change_password, created_by, created_at, last_login_at FROM users ORDER BY username;"
   ```

   There should be at most one row for each username, with roles `SCANNER`, `EXAM_CONTROLLER`, and `SUPER_ADMIN`.
3. Confirm the code no longer uses `InMemoryUserDetailsManager`:

   ```sh
   rg "InMemoryUserDetailsManager" src/main/java
   ```

   It should return no matches.
4. Try an incorrect password for one account. Login should be rejected.
5. If checking an existing database, confirm a submission still has its original scanner username and that `paper_uploader` now has role `EXAM_CONTROLLER`:

   ```sh
   psql -h localhost -U answer_app -d answer_scripts -c "SELECT username, role FROM users ORDER BY username;"
   psql -h localhost -U answer_app -d answer_scripts -c "SELECT u.username, COUNT(*) FROM answer_submissions s JOIN users u ON u.id = s.scanner_user_id GROUP BY u.username ORDER BY u.username;"
   ```

## Phase 17 boundaries

Built: a database-backed user lookup, BCrypt password hashes, a one-time development account bootstrap for an empty users table, login timestamps, and the `EXAM_CONTROLLER` role migration while preserving existing submission ownership references.

Not built in Phase 17: user management screens or account changes, exam creation, per-exam papers or rubrics, scanner exam selection, student lists, background processing, or Phase 18 features.

## Check Phase 18

No new software or API key is needed. Start PostgreSQL and run the app using the **Run** setup above. Flyway creates the user-management audit table on startup. Sign in as `super_admin` and open **Manage users** from Model settings.

1. Create a scanner with a password of at least 10 characters. Sign in as that scanner in a separate private browser session. The login should take the user to **Change password** before allowing other pages. Enter the initial password and a new password; after changing it, sign in with the new password.
2. From the Super Admin session, disable that scanner. Its next request in the existing session should send it back to login, and a new login with its password should be refused.
3. Enable the scanner again, reset its password to a new temporary password, then sign in with the temporary password. The user should be required to change it before continuing; the temporary password works for that change, then the replacement password works at login.
4. If the database has only one enabled Super Admin, try to disable that account. The app should refuse and keep it enabled.
5. Sign in as a scanner and as `paper_uploader` (the Exam Controller). Each should be refused access to `http://localhost:8080/users.html` and `/api/users`.

Successful user creation, enable/disable, password reset, and password change are recorded in `user_management_audit`. To inspect recent entries:

```sh
psql -h localhost -U answer_app -d answer_scripts -c "SELECT actor_user_id, action, target_username, created_at FROM user_management_audit ORDER BY created_at DESC LIMIT 20;"
```

## Phase 18 boundaries

Built: a Super Admin user list and create/enable/disable/reset actions, forced password changes for initial and reset passwords, a change-password page for all roles, immediate disabled-session checks, and a small user-management audit table.

Not built in Phase 18: exam creation, question papers or rubrics attached to exams, scanner exam selection, student lists, password reset by email, self-signup, single sign-on, or Phase 19 features.

## Check Phase 19

No additional software or API key is needed. Start PostgreSQL and run the app using the **Run** setup above. Flyway creates the `exams` table on startup. Sign in as `super_admin` or `paper_uploader` (Exam Controller), then open **Manage exams** from the role page.

1. Create two exams with exam name `Mid Term 1`, subject `DBMS`, year `2`, and different branches: one `CSE` and one `AIML`. The list should show both records.
2. Edit one exam's name, subject, course code, date or status. Choose `CLOSED` to close it (or `OPEN` to reopen it), select **Save changes**, and reload the page. The saved values should remain.
3. Sign in as `scanner` and open `http://localhost:8080/exams.html` directly. Access should be denied.

The branch/year dropdowns are loaded from `config/reference-data.json` on page load. Edit that file and reload **Manage exams** to see the new options. `EXAM_REFERENCE_DATA_FILE` can point to a different JSON file; if it is unset, the app uses `./config/reference-data.json`.

## Phase 19 boundaries

Built: database-backed exams with create/list/edit/open/close operations, reference-data-driven branch and year dropdowns, and access restricted to Exam Controllers and Super Admins.

Not built in Phase 19: attaching question papers or rubrics to exams, scanner exam selection, moving existing data to exams, exam deletion, student lists, automatic reading or marking, or later Module 5 phases.

## Check Phase 20

No additional software is needed. Start PostgreSQL and run the app using the **Run** setup above. Flyway creates the exam-paper and exam-rubric links and attaches existing papers and submissions to the closed `Unassigned (before Module 5)` placeholder exam. Existing stored PDF files are kept in place.

1. Sign in as `paper_uploader` or `super_admin`, open **Question papers**, select an exam, and upload a PDF. Upload another PDF for that same exam. The page should show the second as current and the first in the replaced-file history.
2. Check the database rows and verify both server-named files remain in `storage/`:

   ```sh
   psql -h localhost -U answer_app -d answer_scripts -c "SELECT e.name, p.display_filename, p.stored_filename, p.replaced FROM question_papers p JOIN exams e ON e.id = p.exam_id ORDER BY e.name, p.uploaded_at DESC;"
   ```

3. Open **Rubrics**, select that exam, enter a rubric, save, and reload. The rubric should remain attached to the exam. Exam Controllers and Super Admins can open this page; scanners cannot.
4. For an exam-linked marking check, submit a script after choosing that exam in the scanner page. In **Read and mark submitted scripts**, open that submission. It should show the exam's current paper and rubric without a paper dropdown. Select **Mark with rubric** to check the saved rubric is used; this needs a selected image-capable model and its provider API key configured.
5. Open a submission assigned to `Unassigned (before Module 5)`. Its marking section should still offer the question-paper dropdown. The dropdown includes replaced papers for legacy submissions.

## Phase 20 boundaries

Built: question papers are attached to exams with one current row and retained replaced rows; rubrics are stored per exam; legacy papers and submissions use the closed placeholder exam; marking selects the submission exam's current paper and rubric automatically.

Not built in Phase 20: scanner exam selection, choosing an exam while submitting new pages, student lists, automatic reading or marking, or later Module 5 phases.

## Check Phase 21

No additional software is needed. Start PostgreSQL and run the app using the **Run** setup above. Flyway links existing submission owners to their user IDs; existing submissions remain attached to the closed `Unassigned (before Module 5)` exam.

1. Sign in as a scanner and open **Add answer-script pages**. Before adding pages, choose an open exam. The choice should show its name, subject, branch, and year. Add and submit pages; the receipt should name the chosen exam. Open **View submitted scripts** and confirm the submission displays the exam label.
2. To check closure enforcement, open the scanner page, choose an open exam, and add a page. In a separate Exam Controller or Super Admin session, close that exam. Return to the already-open scanner page and submit. The server should refuse the request (HTTP 409); no submission is created for the closed exam. If another exam is open, select it and submit the queued pages there.
3. Open older submissions as the scanner that owned them before this phase. Their pages should still load, and the exam label should be `Unassigned (before Module 5)`. To inspect the migrated owner and exam links:

   ```sh
   psql -h localhost -U answer_app -d answer_scripts -c "SELECT s.id, e.name AS exam, u.username AS scanner, COUNT(p.id) AS pages FROM answer_submissions s JOIN exams e ON e.id = s.exam_id JOIN users u ON u.id = s.scanner_user_id LEFT JOIN answer_pages p ON p.submission_id = s.id GROUP BY s.id, e.name, u.username ORDER BY s.created_at DESC LIMIT 20;"
   ```

## Phase 21 boundaries

Built: scanners choose an open exam before adding pages; each submission stores the exam ID and scanner user ID; “My submissions” shows the exam label; the server rechecks exam status at submission time; existing submission owners are migrated by username to user IDs.

Not built in Phase 21: browse/filter views, student identity or roll-number reading, automatic processing, cloud storage, or later Module 5 phases.

## Check Phase 22

No additional software or API key is needed. Start PostgreSQL and run the app using the **Run** setup above. Sign in as `super_admin`, `paper_uploader` (Exam Controller), or `scanner`.

1. Create two exams with different branches, subjects, or years, then submit several scripts to them as a scanner. Open **Browse**. It should group exams by branch and year, and show a submission count at each branch, year, and exam. The counts should match the submissions list.
2. Select a branch, then a year, then an exam from **Browse**. Each link should open **Submissions** with that filter selected. In the list, apply Exam, Subject, Branch, and Year individually, then combine two or more filters; the displayed rows should satisfy every selected filter.
3. Each row should show the submission UUID as its number, exam, subject, branch, year, scanner display name, page count, and submission time. As a scanner, sign in with a different account and confirm only that scanner's own submissions and page images appear.
4. Sign in as `paper_uploader` (Exam Controller). The list should show submissions from all scanners, but no reading, marking, or model-settings actions should appear. Direct requests to the Super Admin-only reading and settings pages remain denied.
5. The list and Browse counts come from database queries. To compare recent rows and page counts with the database:

   ```sh
   psql -h localhost -U answer_app -d answer_scripts -c "SELECT s.id AS submission_number, e.name AS exam, e.subject_name, e.branch, e.year, u.display_name AS scanner, COUNT(p.id) AS page_count, s.created_at AS submitted_at FROM answer_submissions s JOIN exams e ON e.id = s.exam_id JOIN users u ON u.id = s.scanner_user_id LEFT JOIN answer_pages p ON p.submission_id = s.id GROUP BY s.id, e.id, u.id ORDER BY s.created_at DESC, s.id DESC LIMIT 20;"
   ```

## Phase 22 boundaries

Built: shared role-scoped submission filters, database-backed submission rows and branch/year/exam counts, and Browse links that open the corresponding filtered list. Scanner page previews remain available only for the scanner's own submissions.

Not built in Phase 22: section or roll-number filters, student identity, automatic reading or marking, or the Phase 23 access review.

## Check Phase 23

No additional software, API key, or database is needed for the automated access checks. From the project folder run:

```sh
mvn test
```

The tests check that scanners are refused exam, question-paper, rubric, user, settings, transcription, and marking routes; Exam Controllers are refused user, model-settings, transcription, and marking routes; disabled sessions are redirected to sign-in; and scanner page-image lookups include the signed-in scanner as an ownership condition. The tests use an isolated web context and a mocked database service, so they do not alter the app database.

For the manual ownership check, start PostgreSQL and the app using **Run** above. Sign in as scanner A, open one of their page images from **Submissions**, and copy its URL. Sign out, sign in as scanner B, and open the copied URL. The app should return `404` and not display the image.

### Endpoint access review

All authenticated access below also requires an enabled account. Unknown routes require authentication by default.

| Page or endpoint | Allowed role(s) | Extra check |
|---|---|---|
| `/`, `/actuator/health`, `/login` | Public | Health reports app/database state only. |
| `/csrf` | All roles | Requires an authenticated session. |
| `/upload.html`, `GET /api/submissions/open-exams`, `POST /api/submissions` | SCANNER | Upload stores the authenticated scanner user ID; exam openness and submitted page numbers are rechecked server-side. |
| `GET /api/submissions` | SCANNER | Returns the signed-in scanner's submissions only. |
| `GET /api/submissions/{submissionId}/pages/{pageNumber}/image` | SCANNER | Query checks submission, page number, and signed-in scanner; another owner's page returns `404`. |
| `/submissions.html`, `/browse.html`, `/api/submission-browser/**` | SCANNER, EXAM_CONTROLLER, SUPER_ADMIN | Scanner rows, filter options, counts, and image previews are scoped to their own submissions. |
| `/exams.html`, `/api/exams/**` | EXAM_CONTROLLER, SUPER_ADMIN | Exam changes record the authenticated creator/updater. This includes exam question-paper upload requests. |
| `/question-paper.html`, `/api/question-papers/**` | EXAM_CONTROLLER, SUPER_ADMIN | Paper rows are tied to their exam and uploader. There is no direct PDF download route. |
| `/rubric.html`, `/api/rubrics/**` | EXAM_CONTROLLER, SUPER_ADMIN | Rubric changes record the authenticated editor. |
| `/settings.html`, `/api/settings/**` | SUPER_ADMIN | Provider keys remain in environment configuration, not settings records. |
| `/users.html`, `/api/users/**` | SUPER_ADMIN | Management actions are audited; the last enabled Super Admin cannot be disabled. |
| `/transcription.html`, `/api/transcriptions/**`, `/api/marking/**` | SUPER_ADMIN | Reading, page-image access in this workflow, and marking are restricted to Super Admin. |
| `/change-password.html`, `POST /api/auth/change-password` | All authenticated roles | Verifies the current password and enforces password length rules. |
| `POST /login`, `POST /logout` | Public login; any authenticated role may log out | Disabled accounts cannot authenticate; the account-state filter invalidates an existing disabled session on its next request. |

Built in Phase 23: endpoint and file access review, automated role/disabled-account checks, and an ownership-condition test for scanner page fetches.

Not built in Phase 23: new user-facing features, student identity, automatic processing, or later modules.
