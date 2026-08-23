import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'

import App from './App'

test('shows the engineering foundation status', () => {
  render(<App />)

  expect(
    screen.getByRole('heading', { level: 1, name: 'FinGuard Copilot' }),
  ).toBeInTheDocument()
  expect(screen.getByText('Engineering foundation ready')).toBeInTheDocument()
})
