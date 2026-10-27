# Release checklist: v1.0.0 open source

Everything technical is prepared on the `release/open-source-1.0` branch. What's left are the approval steps.
Run the commands from the repository root on your Mac (`gh` logged in as `heynol`). The release files are in the
local `dist/` folder, which is not in git.

## 0. Before you start

- [ ] **Back up the signing key.** Without `~/Keystores/heynol-sideload.jks` and the password in
      `~/Keystores/heynol-sideload.properties`, no update to Afterglow (or Theatre Bar HUD) can ever be installed over
      the released version. Put both into your password manager or an encrypted backup, not into iCloud Drive as
      plain files.
- [ ] Check that `dist/` holds `afterglow-1.0.0.apk`, `afterglow-1.0.0.apk.sha256` and `afterglow-demo.mp4`:

```bash
ls -la dist && (cd dist && shasum -a 256 -c afterglow-1.0.0.apk.sha256)
```

## 1. Merge the PR

```bash
gh pr merge release/open-source-1.0 --merge --delete-branch
```

```bash
git checkout main && git pull
```

## 2. Rename the repository (optional, your call)

Suggested: `afterglow`. GitHub redirects the old URLs, but the README badges and links should point to the new
name, so the second command updates them and pushes:

```bash
gh repo rename afterglow
```

```bash
grep -rl "heynol/Android-TV-Visualizer\|heynol.github.io/Android-TV-Visualizer" README.md CHANGELOG.md dist/RELEASE_NOTES.md | xargs sed -i '' -e 's#heynol/Android-TV-Visualizer#heynol/afterglow#g' -e 's#heynol.github.io/Android-TV-Visualizer#heynol.github.io/afterglow#g' && git commit -am "Point links to the renamed repository" && git push
```

## 3. Description, homepage, topics

```bash
gh repo edit --description "Winamp/MilkDrop-style music visualizations over YouTube on Android TV / Google TV. 24 GLSL visuals, real-time audio analysis." --homepage "https://heynol.com" --add-topic android-tv --add-topic music-visualizer --add-topic glsl --add-topic milkdrop --add-topic kotlin --add-topic google-tv --add-topic shaders --add-topic audio-visualizer
```

## 4. Make it public

```bash
gh repo edit --visibility public --accept-visibility-change-consequences
```

## 5. Publish release v1.0.0

A **draft** release with the APK, checksum and demo MP4 already exists (made from `dist/RELEASE_NOTES.md`).
Review it on GitHub (Releases → Draft), then publish. Publishing creates the tag `v1.0.0` on `main`:

```bash
gh release edit v1.0.0 --draft=false --latest
```

If the draft is gone or you'd rather create the release from scratch:

```bash
gh release create v1.0.0 dist/afterglow-1.0.0.apk dist/afterglow-1.0.0.apk.sha256 dist/afterglow-demo.mp4 --target main --title "Afterglow 1.0.0" --notes-file dist/RELEASE_NOTES.md --latest
```

## 6. Web UI only

- [ ] **Social preview:** Settings → General → Social preview → upload `docs/media/social-preview.png` (1280×640).
- [ ] Optional: pin the repository on your profile.

## 7. Shader Lab on GitHub Pages (optional)

The workflow `.github/workflows/shader-lab-pages.yml` publishes only `tools/shader-lab` and the shader assets. It
does nothing until it's switched on. Free GitHub Pages needs the repository to be public (step 4).

```bash
gh api -X POST "repos/{owner}/{repo}/pages" -f build_type=workflow
```

```bash
gh variable set SHADER_LAB_PAGES --body true
```

```bash
gh workflow run shader-lab-pages.yml
```

The lab is then at `https://heynol.github.io/<repo-name>/tools/shader-lab/` (the root redirects there).

## Decide

- [ ] `.claude/launch.json` is tracked: a one-entry config that starts the Shader Lab server for Claude Code. Keep it
      (harmless, documents the dev server) or remove it with `git rm --cached .claude/launch.json` and add `.claude/`
      to `.gitignore`.
- [ ] Commit author e-mail: all commits carry `the.pascal.heynol@gmail.com`. It becomes public with the repository.
      Rewriting it would need a history rewrite, so it's left as is.
