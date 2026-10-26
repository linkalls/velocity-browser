# Cefrium native ad blocking: coverage and fail-safe boundaries

Our request interceptor handles outgoing HTTP(S) subrequests. It uses
Velocity Block Lite's intentionally small domain-rule grammar and the bundled
Peter Lowe domain snapshot. Cefrium's own built-in EasyList is separate.
The implementation never parses remote JS, injects a page-to-native bridge,
or claims support for Gecko WebExtension/XPI APIs.

## Recent improvements

- Immutable allow/deny domain sets, boundary-safe subdomain matching.
- HTTPS and HTTP only; top-level navigations are never blocked.
- Programmatic per-site exemptions for the *additional* network blocker:
  exact site host matching, so `news.example.com.evil.test` cannot inherit
  `news.example.com` exemption.
- Ordinary-site exemptions use app-private preferences, with a bounded and
  normalized host set. Private-site exemptions are process-memory-only and
  never added to the normal whitelist.
- Per-tab URL is volatile so background CEF request interception sees
  current page hosts. Immutable snapshot sets are safely swapped by UI thread.
- Menu toggles the extra blocker globally or exempts the current site. The
  state of Cefrium's **separate built-in EasyList** remains unaffected.

## Outstanding

Element hiding/cosmetic CSS rules, dynamic filter list updates, custom user
rules, AdGuard-compatible extended syntax, CNAME cloaking and YouTube-style
first-party/inline ads remain unimplemented. Do not claim full uBlock Origin
parity. Test known breakage examples and site exceptions before enabling more
aggressive lists. Adding unreviewed remote JavaScript to page context is a
security regression, not an adblock feature.
