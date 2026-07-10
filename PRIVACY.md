# Privacy and Data Handling

This document describes the behavior of the open-source Twidget app and bridge.
An operator who deploys a modified or self-hosted bridge is responsible for
describing any different practices.

## Data stored on the Android device

Twidget stores configured X/Twitter usernames, app and widget preferences,
fetched profile statistics, post analytics, and local history on the device.
Optional official X API credentials, self-hosted bridge tokens, and cached
bearer tokens are protected using Android Keystore-backed encryption.

Android cloud backup is disabled for the app. Removing an account from Twidget
removes its app-managed local profile and history. Uninstalling the app removes
its app-private data under normal Android behavior.

## Network requests

The selected data source determines where requests go:

- **FxTwitter:** the app contacts FxTwitter directly for public profile and
  recent-post information.
- **Shared Twidget bridge:** the app contacts the hosted bridge when that source
  is selected or when shared history has been explicitly enabled.
- **Self-hosted bridge:** the app contacts the URL configured by the user.
- **Official X API:** the app contacts X directly using credentials supplied by
  the user.
- **Updates:** the About screen checks this repository's GitHub Releases API;
  update downloads come from the matching GitHub release asset.
- **Images and links:** profile images may be fetched from URLs returned by a
  provider or from Unavatar, and links opened by the user are handled by the
  selected browser or app.

Those third-party services receive normal network metadata such as the user's
IP address and user agent and apply their own privacy policies.

## Shared history

Shared history is opt-in. Ordinary direct FxTwitter lookups do not register an
account in the hosted history pool. When sharing is enabled, the bridge may
store the X/Twitter username, public account metrics, metric provenance, and
daily sample timestamps so participating users can receive real historical
data. Client-supplied historical backfill is disabled by default.

The bridge can use PostgreSQL for history and Redis for shared request limits,
caches, registration budgets, and scheduled-job locks. Retention and inactive
account deletion are operator-configured. Operators can delete an account's
stored history through the protected administration endpoint.

Do not submit secrets or private account content to the shared-history service.
The bridge is designed around public profile metrics and does not need a user's
X password.

## Logs and operational data

The hosted platform and bridge may process short-lived request metadata,
errors, health information, and abuse-prevention counters needed to operate and
secure the service. Application debug logs are stored locally and are not
uploaded automatically.

## Questions and deletion requests

For privacy questions or deletion of history held by the maintainer-operated
bridge, contact the maintainer through [tjg.gg](https://tjg.gg) and identify the
X/Twitter username to remove. Do not send API keys, passwords, or bridge tokens.
