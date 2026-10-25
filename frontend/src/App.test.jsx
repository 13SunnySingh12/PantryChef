import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'
import App from './App.jsx'

test('renders the app heading', () => {
  render(<App />)
  expect(screen.getByRole('heading', { level: 1, name: 'PantryChef' })).toBeTruthy()
})
