import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SkillMdTab } from './SkillMdTab'

const skillMdContent = `---
name: my-skill
version: "1.0.0"
description: "AI embedding for code review"
allowed-tools: true
---

# My Skill

This skill does code review.

## Usage

Install and run with your AI agent.
`

describe('SkillMdTab', () => {
  it('AC-S142a-11: frontmatter rendered with tok-key / tok-str classes', () => {
    const { container } = render(<SkillMdTab content={skillMdContent} />)
    expect(container.querySelectorAll('.tok-key').length).toBeGreaterThan(0)
    expect(container.querySelectorAll('.tok-str').length).toBeGreaterThan(0)
  })

  it('AC-S142a-11: --- delimiters rendered as tok-sep', () => {
    const { container } = render(<SkillMdTab content={skillMdContent} />)
    expect(container.querySelectorAll('.tok-sep').length).toBeGreaterThanOrEqual(2)
  })

  it('AC-S142a-11: markdown body rendered below frontmatter', () => {
    render(<SkillMdTab content={skillMdContent} />)
    expect(screen.getByText('My Skill')).toBeTruthy()
    expect(screen.getByText('Usage')).toBeTruthy()
  })

  it('content=undefined → skeleton loader', () => {
    const { container } = render(<SkillMdTab content={undefined} />)
    expect(container.querySelector('.animate-pulse')).toBeTruthy()
    expect(screen.queryByTestId('skill-md-tab')).toBeNull()
  })

  it('content=null → 暫不可用 message', () => {
    render(<SkillMdTab content={null} />)
    expect(screen.getByText('SKILL.md 暫不可用')).toBeTruthy()
  })
})
