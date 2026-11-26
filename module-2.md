# Module 2: Read the pages (AI transcription)

## Goal
Take the pages of one submitted answer script (stored by Module 1) and have an AI model write out the text the student wrote, with the question numbers the student wrote. Show the pages and the text side by side so I can judge the result.

## Who does what
- **Super Admin** (new role): logs in, chooses the AI model, can switch or replace it at any time, and for now is the one who runs "Read pages" on a submission. Who triggers reading in the final system is decided later.
- Scanner and question paper uploader: no change.

## Not in this module
- Extracting roll number, branch, year, student info, or any other metadata
- Marking, rubrics, reading the question paper
- Any testing, benchmarking, scoring, side-by-side comparison, ranking or approval of models
- Cloud storage, format conversion, zipping

## Phase 8: Super Admin and model settings
**Build**
- Super Admin login: one seeded account for the demo, like the scanner. Only the Super Admin can open the settings page.
- A list of available models kept in a config file `models.json`. Each entry has: an id, the provider, the exact model name, the endpoint if needed, the name of the environment variable that holds its API key, and whether it can read images. Adding a model later means editing this file only, not code.
- Starting entries: `gpt-6-luna` (OpenAI), `gemini-3.8-flash` (Google), `claude-haiku-5-5` (Anthropic).
- Settings page: shows the list and marks which models have an API key present (yes or no, never the key itself). A model with no key is clearly marked and cannot be selected. The Super Admin picks one and saves.
- The chosen model is saved in the database and is still selected after a restart.
- API keys come from environment variables or a local config file kept out of git. Never in code, pages, logs or the database.

**How to check**
1. Log in as Super Admin, choose a model, save.
2. Restart the app. The choice is still selected.
3. Log in as a scanner. The settings page is refused.
4. A model without a key shows as unavailable.

## Phase 9: Read one submission's pages
**Build**
- A submissions list for the Super Admin (submission number and page count). Opening one shows its pages in order.
- A "Read pages" button. When pressed:
    1. The submission's pages are taken in page-number order and each is shrunk (longest side 1600 px, JPEG) before sending.
    2. All pages go to the chosen model in one request, together with the instructions below.
    3. The reply is saved and parsed.
- The instructions sent with the pages live in one editable text file, not in code:
  > Transcribe VERBATIM exactly what the student wrote. Do not correct spelling, grammar or code errors. Keep the question number the student wrote for each answer (for example 1a, 2, 4); continue an answer across pages. Write [illegible] for unreadable text. Describe each diagram as [DIAGRAM: labelled parts and flow]. Return only JSON: {"answers":[{"q":"1a","text":"..."}]}
- Stored for every run: the submission, the model (provider and exact name), the start time, success or failure, the raw reply, the parsed answers, and the error message if any.
- Result page: the page images in order, beside the text grouped by the question numbers the student wrote.
- If the model call fails, or the reply is not valid JSON, show the problem in plain words. Nothing is marked as done and the button can be pressed again.

**How to check**
1. Use a real script of 3 to 5 pages submitted through Module 1.
2. Press "Read pages". The text appears next to the pages.
3. Put a wrong API key in place. A clear error is shown, not a blank page.

## Phase 10: Switch the model
**Build**
- The Super Admin changes the model in settings, then presses "Read pages" again on the same submission.
- Every run is saved as its own result with the model name and time. The page shows the latest result. Older results are in a simple list and open one at a time.
- No side-by-side view, no scores, no ranking.

**How to check**
1. Run with model A, switch to model B, run again.
2. The latest result shows model B's name.
3. Model A's earlier result still opens from the list.

## Details Codex may choose
Layout, wording and styling. Pick the simplest option and add one line to DECISIONS.md.

## Decided later (do not build)
- Who triggers reading in the real flow, and whether it happens automatically after Submit
- What to do when results are unsatisfactory, beyond switching the model
