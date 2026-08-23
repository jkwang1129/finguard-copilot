import './App.css'

const foundations = [
  {
    name: 'Java Agent Runtime',
    detail: 'Spring Boot, Flyway, MySQL, OpenAPI, and observable health.',
  },
  {
    name: 'Python Evaluation',
    detail: 'Typed JSONL datasets managed reproducibly with Python 3.12 and uv.',
  },
  {
    name: 'Validated React UI',
    detail: 'Accessible React and TypeScript foundation tested with Vitest.',
  },
]

function App() {
  return (
    <main className="app-shell">
      <header className="hero">
        <p className="eyebrow">AI-assisted financial review</p>
        <h1>FinGuard Copilot</h1>
        <p className="status">
          <span aria-hidden="true" className="status-dot" />
          Engineering foundation ready
        </p>
        <p className="summary">
          A read-only, evidence-first Agent system with independently testable
          runtime, evaluation, and demonstration boundaries.
        </p>
      </header>

      <section aria-labelledby="foundation-heading">
        <h2 id="foundation-heading">Phase 0 boundaries</h2>
        <ul className="foundation-grid">
          {foundations.map((foundation) => (
            <li key={foundation.name}>
              <h3>{foundation.name}</h3>
              <p>{foundation.detail}</p>
            </li>
          ))}
        </ul>
      </section>
    </main>
  )
}

export default App
