# Manual smoke tests (Windows 11)

CI builds and unit-tests everything on Linux and on `windows-latest`,
including a real Windows Credential Manager round trip. These checks cover
what CI can't: the real window, real keys and real providers. Each takes a few
minutes.

**Get the build.** Download `kickoff-windows-debug-installer` from the latest
green run of **Kickoff CI** on the branch (GitHub → Actions → Kickoff CI → the
run → Artifacts) and run the `Kickoff_*_x64-setup.exe` inside it. It installs
per-user and needs no admin rights.

## M0 + M1: shell, key library, free AI setup

1. **First start shows the setup wizard.** Launch Kickoff. Expect the "Let's
   connect a free AI model" screen.
2. **Free cloud key, under 2 minutes.** Click *Get started*. Without Ollama,
   *Google Gemini* should be marked *Recommended* and *Free tier · no card*.
   - Click *Continue*, then *Open key page*. The browser opens Google AI Studio.
   - Create a key, paste it, and click *Test and save key*.
   - Expect "You're ready" and a line naming the model that answered.
3. **The key is in Credential Manager, not on disk.**
   - Open *Control Panel → Credential Manager → Windows Credentials*. Expect an
     entry ending in `.dev.kickoff.studio`.
   - Open `%APPDATA%\dev.kickoff.studio` and search the folder for your key
     text (for example with VS Code's search). Expect no matches, including in
     the `logs` folder.
4. **A bad key fails clearly.** In *Key Library → Add key*, choose *Groq* and
   paste `nonsense`. Click *Save key*. Expect a plain message that the key was
   rejected.
5. **Ollama path (if you have a capable GPU).** Install Ollama and run
   `ollama pull qwen3:8b`. Then choose *Settings → Run setup again*.
   - *Ollama on this PC* should be *Recommended*.
   - *Test Ollama* should succeed with no key.
6. **AI settings.**
   - In *Settings → Models per task*, click *Check* next to Interview. Expect
     "Answered by …".
   - Reorder the fallback chain and restart the app. Expect the order to be kept.
   - *Today's usage* should count the calls you just made.
7. **Rate-limit toasts (optional).** Click *Check* repeatedly against a free
   provider until it rate limits you. Expect a toast in the bottom right, such
   as "… is rate limited. Retrying in N s." or "Switched from … to …".
8. **Theme and folder.** Switch to Light, then *Match Windows*. Pick a projects
   folder. Restart and check both are kept.

## M2: producer interview

Use any working free setup from M1. Ollama or Gemini both work.

1. **Start from one sentence.** On *Projects*, click one of the example ideas
   (or type your own) and click *Start interview*.
   - Your sentence appears, and the producer's reply streams in word by word.
   - It asks exactly one question.
   - On the right, *One-line pitch* fills in.
2. **The brief fills in as you talk.** Answer three or four questions. After
   each answer, the matching field on the right should update, and the
   progress bar and "N of 14 settled" should go up. Once you give a working
   title, the project is renamed to it.
3. **Honesty.** Answer one question with something too big, for example "an
   open-world MMO with 200 hours of story". Expect the producer to push back
   on scope, either in its next reply or under *Producer notes* on the right.
4. **Your edits win.**
   - Click *Genre* on the right, type your own genre and click *Save*. It shows
     *Edited by you*.
   - Click it again and choose *Ask the producer to revisit*. It shows
     *Revisit next*, and the producer brings it up after your next answer.
   - If the producer then proposes a different genre, it appears as "Producer
     suggests: …" with *Use it* and *Keep mine*. Your own text stays until you
     choose.
5. **Recovering from failures.** Turn off Wi-Fi (or stop Ollama) and send an
   answer.
   - Your message stays.
   - A red notice explains what failed and offers *Try again*.
   - Reconnect and click *Try again*. The reply arrives, and the brief picks up
     that answer too.
6. **Everything is saved.** Close Kickoff and open it again. The project is in
   the list with its pitch and progress. Opening it shows the whole
   conversation and the brief as you left it.
7. **Moving on.** Click *Continue to research* before the brief is complete.
   - A dialog lists what's missing; *Keep talking* closes it.
   - *Continue anyway* marks the interview finished. Research itself arrives
     in M4.
8. **Deleting.** On *Projects*, delete a test project and confirm. It
   disappears from the list.

If anything fails, the log is in `%APPDATA%\dev.kickoff.studio\logs`. Keys are
redacted, so it is safe to share.
