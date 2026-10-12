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

If anything fails, the log is in `%APPDATA%\dev.kickoff.studio\logs`. Keys are
redacted, so it is safe to share.
