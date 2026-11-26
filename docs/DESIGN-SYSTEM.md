# LifeTag design system: "The tag and the thread"

The identity comes from the patient wristband: the one object that follows a patient from registration to billing.

- **Tag**: a surface with one clipped corner and a punched hole. Used for the dashboard stat strip, the patient ID band, the sign-in sheet and the logo.
- **Thread**: a single ink line. Used under page headers, in timelines, in the sidebar and in the sign-in care journey.
- **Active = yellow tag**: current nav item, tabs and role switch all share it.
- **Status = edge + shape + word**: filled circle (done), half (in progress), triangle (needs action), ring (idle). Never colour alone.

Files: `css/style.css` (tokens, base, auth), `css/dashboard.css` (shell and components), `css/responsive.css` (breakpoints, touch).
Tokens are CSS variables at the top of `style.css` (colour, type, spacing, radii, shadow, layout). Dark mode overrides them under `[data-theme="dark"]`.
Fonts (Bricolage Grotesque, Instrument Sans) are self-hosted in `css/fonts/`, so nothing loads from the internet.
Legacy variable names (`--teal-500`, `--coral-500`, `--navy-950` ...) are aliased to the new tokens so older inline styles still work.
