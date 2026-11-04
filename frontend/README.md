# Frontend

The pilot uses plain HTML, CSS and JavaScript served by the local Java backend.
Sources are in `backend/src/test/resources/spike/ui`; regression tests are in
`backend/src/test/js`. There is no React/TypeScript app or frontend build.

Run tests from the repository root with Node.js 24.22.1:

```shell
node --test backend/src/test/js/ui-connection.test.cjs
```

The browser sends HTTP commands and receives state snapshots over SSE. The backend
owns operation state. A framework will be considered only when requirements justify it.
