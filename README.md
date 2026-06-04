# codeanalyzer-java documentation

This branch (`docs`) contains the documentation site for
[**codeanalyzer-java**](https://github.com/codellm-devkit/codeanalyzer-java) — the
WALA + Javaparser static-analysis backend behind CodeLLM-DevKit's Java support.

The site is built with [Astro](https://astro.build/) and
[Starlight](https://starlight.astro.build/), and is deployed to GitHub Pages.

> **Looking for the analyzer source code?** It lives on the
> [`main`](https://github.com/codellm-devkit/codeanalyzer-java/tree/main) branch.

## Local development

```bash
npm install
npm run dev          # start the dev server at http://localhost:4321
```

| Command | Action |
|---------|--------|
| `npm install` | Install dependencies |
| `npm run dev` | Start the local dev server |
| `npm run build` | Build the production site to `./dist/` |
| `npm run preview` | Preview the built site locally |

## Structure

```
src/
├── assets/                 logos
├── styles/docs.css         theme overrides
├── content.config.ts       Starlight content collection
└── content/docs/           the documentation pages (MDX)
        ├── index.mdx               landing page
        ├── what-is-codeanalyzer.mdx
        ├── quickstart.mdx
        ├── installing.mdx
        ├── guides/                 architecture, analysis levels, build, incremental
        ├── reference/              CLI options + examples
        ├── schema/                 output JSON schema
        ├── frameworks/             entry points + CRUD detection
        └── integration/            Python SDK (CLDK)
astro.config.mjs            site + sidebar configuration
```

## Deployment

`.github/workflows/deploy.yml` builds the site and publishes it to GitHub Pages
on every push to the `docs` branch.

## License

Apache 2.0 — see [LICENSE](./LICENSE).
