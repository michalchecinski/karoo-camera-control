# Copilot code review

The repository review skill lives at
[`.github/skills/code-review/SKILL.md`](../.github/skills/code-review/SKILL.md).
Its name and description identify it for Copilot when reviewing pull requests
or diffs. It supplements the repository-wide
[Copilot instructions](../.github/copilot-instructions.md) and [AGENTS.md](../AGENTS.md).
Discovery and execution depend on the Copilot environment; the file does not
configure automatic review requests or GitHub Actions.

The skill directs reviewers to the current feature docs, then checks only the
integration paths affected by the change: GoPro command confirmation and
pairing, recording ownership and manual override, Karoo lifecycle, Android UI,
and diagnostic privacy. Roadmap proposals are distinguished from shipped
behaviour. Findings need a concrete failure path and user impact; unavailable
checks are reported separately from proven defects.

## Reference projects

The review guidance was informed by extensions listed in
[awesome-karoo](https://github.com/timklge/awesome-karoo):

- [Karoo Home Assistant Companion](https://github.com/michalchecinski/karoo-hass-companion/blob/HEAD/.github/copilot-instructions.md)
  uses focused, evidence-based review instructions covering product boundaries,
  action safety, connectivity, and Karoo UI.
- [WPrimeKarooExtension](https://github.com/apopovsky/WPrimeKarooExtension/blob/HEAD/.github/copilot-instructions.md)
  points Copilot to canonical project docs and requires checking the resolved
  Karoo SDK rather than assuming upstream APIs are available.

Those projects provided Copilot instructions rather than a skill at this path
when inspected. The skill format and review-focused directory follow
[GitHub's agent skill guidance](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/add-skills).
