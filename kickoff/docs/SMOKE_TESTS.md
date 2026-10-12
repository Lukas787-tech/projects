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

## M3: voice

You need a microphone and speakers (a headset is easiest).

1. **Voice step in setup.** Choose *Settings → Run setup again* and get to
   *Talk instead of typing?*.
   - *Download* the speech model (142 MB). The progress bar fills and the
     button turns into *Ready*.
   - *Listen* plays the producer's voice (Windows voices unless you've set up
     another one).
   - *Continue*.
2. **Talk.** Open a project and click the microphone next to *Send*.
   - Speak a sentence. The red level meter moves while you talk.
   - Click *Send*. "Transcribing…" appears briefly, then your words show as
     your message and the producer answers.
   - The reply is read aloud while it is still appearing, and a bar shows
     "The producer is speaking." with *Stop (Esc)*.
3. **Interrupt.** While the producer speaks, press Esc: it stops at once.
   Start talking (click the microphone) during a reply: it stops too.
4. **Hold F2.** Hold F2, speak, release. Your answer is sent on release.
5. **Hands-free.** In *Settings → Voice*, turn on *Hands-free*. Click the
   microphone, speak, then pause: recording stops by itself and sends.
6. **Review first.** Turn on *Review what I said before sending*. After
   speaking, the transcript appears in the message box instead of being sent.
7. **Mute.** Click the speaker icon next to the project title. The next reply
   is text only; click again to hear replies.
8. **Piper.** In *Settings → Voice → Piper*, click *Install Piper* (22 MB),
   then download the selected voice (about 60 MB). Click *Preview*. Move Piper
   to the top of the list and check that replies use it.
9. **Fish Audio (optional, needs a key).** Add a Fish Audio key in the Key
   Library, then in *Settings → Voice* click *Check key*.
   - Expect either "the free … model answered", a note that it spends your
     credits, or a clear explanation of why it can't be used. In that case
     replies fall back to Piper or Windows voices.
   - Search for a voice, *Listen*, then *Use* it.
   - Set a small monthly limit (for example 500 characters), have a short
     conversation, and expect warnings at 80% and 95%.
10. **Fallback.** With Fish Audio first in the list, turn off Wi-Fi and send a
    typed answer. A toast says Fish Audio couldn't speak and the next engine
    takes over.
11. **Text only.** Turn off *Talk with the producer*. The microphone and the
    speaker icon disappear, and nothing is read aloud.

## M4: research and references

You need an AI provider set up. A free Tavily key (app.tavily.com, no card)
makes results verified; step 1 runs without one.

1. **Without a search key.** Open a project, *Continue to research* (or the
   *Research* button once the interview is finished).
   - A yellow notice says there is no web search key and links to the Key
     Library.
   - Click *Find references*. A progress line shows "Planning searches…",
     then "Searching… n of m".
   - Comparable games appear with an *Unverified* badge and no link. Poly
     Haven and Sketchfab assets still appear (with thumbnails), each with a
     license badge.
2. **With Tavily.** Add the Tavily key in the Key Library, go back, click
   *Find more*.
   - The header shows "Web search: Tavily · n of 1,000 credits used this
     month".
   - Comparable games now have a link and no *Unverified* badge (a made-up
     title would stay unverified with "Search didn't find this game").
   - Mood references appear under *Mood*, some as images.
   - Kenney or OpenGameArt pages may appear under *Assets*.
3. **Triage with the keyboard.** Press → to keep, ← to reject. Kept items
   collect on the right; rejected ones under *Rejected (n)*. Press Ctrl+Z:
   the last one comes back. Click ✕ next to a kept item: it returns to the
   queue.
4. **Licenses.** Under *Assets*, check:
   - A Poly Haven item says *Usable · CC0 1.0*, shows "Powered by Poly
     Haven" and a credit line.
   - A Sketchfab item with "CC Attribution" says *Usable · CC BY 4.0*; one
     with a NonCommercial license says *Reference only* and explains why.
   - OpenGameArt and itch.io items say *License unknown*.
   - Click a source link: it opens in your browser. Note any item whose
     license on the page differs from the badge.
5. **More like this.** On a reference you like, press ↑. It moves to *Kept*
   and a few similar ones join the queue.
6. **Refine the brief.** Keep a few mood references and reject a game, then
   click *Refine brief*. Suggestions show the current and the new value with a
   reason. *Apply* one, go back to the interview: the brief shows the new
   value as edited by you.
7. **Restart.** Close and reopen the app. The references and your reactions
   are still there.

If anything fails, the log is in `%APPDATA%\dev.kickoff.studio\logs`. Keys are
redacted, so it is safe to share.
