# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Branch names

Give every new branch a name that says what the change is, so the branch list
and PR history read on their own.

- Format: `<type>/<short-description>`, all lowercase, words joined with `-`.
- `<type>` matches the Conventional Commits type used in this repo's commit
  messages: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`.
- `<short-description>` is 2–5 words naming the change, not the process:
  `feat/proxy-links-instead-of-vless`, not `feat/update` or `feat/changes`.
- Add the area as a prefix inside the description when it helps:
  `fix/engine-dns-leak`, `feat/app-update-check`, `docs/design-profiles-sketch`.
- Keep it under about 50 characters. An issue number may go at the end
  (`fix/engine-crash-on-reconnect-42`); leave out dates, usernames and random
  suffixes.
- Do not use generated names such as `claude/awesome-cray-etk7n4` or
  `ccr-1b1168b8-pf8zq4`. If a session hands you one, create a meaningful branch
  for the work instead and push that.

Examples from past changes:

| Change | Branch |
| --- | --- |
| Accept SOCKS5 and HTTP(S) proxy links in place of VLESS | `feat/socks-http-proxy-links` |
| Check for updates from the About screen | `feat/app-update-check` |
| Pause/resume and check IP from the notification | `feat/notification-pause-ip-check` |
| Save setups as profiles | `feat/connection-profiles` |
| Fix the PayPal.me donate amounts | `fix/donate-amounts` |
