The Complete Guide to Building Skills for Claude
================================================

Contents
--------
1. Introduction
2. Fundamentals
3. Planning and design
4. Testing and iteration
5. Distribution and sharing
6. Patterns and troubleshooting
7. Resources and references


Introduction
------------
A skill is a set of instructions - packaged as a simple folder - that teaches Claude
how to handle specific tasks or workflows. Skills are one of the most powerful
ways to customize Claude for your specific needs. Instead of re-explaining your
preferences, processes, and domain expertise in every conversation, skills let you
teach Claude once and benefit every time.

Skills are powerful when you have repeatable workflows: generating frontend
designs from specs, conducting research with consistent methodology, creating
documents that follow your team's style guide, or orchestrating multi-step
processes. They work well with Claude's built-in capabilities like code execution
and document creation. For those building MCP integrations, skills add another
powerful layer helping turn raw tool access into reliable, optimized workflows.

This guide covers everything you need to know to build effective skills - from
planning and structure to testing and distribution. Whether you're building a
skill for yourself, your team, or for the community, you'll find practical patterns
and real-world examples throughout.

What you'll learn:
- Technical requirements and best practices for skill structure
- Patterns for standalone skills and MCP-enhanced workflows
- Patterns we've seen work well across different use cases
- How to test, iterate, and distribute your skills

Who this is for:
- Developers who want Claude to follow specific workflows consistently
- Power users who want Claude to follow specific workflows
- Teams looking to standardize how Claude works across their organization

Two Paths Through This Guide:
Building standalone skills? Focus on Fundamentals, Planning and Design, and
category 1-2. Enhancing an MCP integration? The "Skills + MCP" section and
category 3 are for you. Both paths share the same technical requirements, but
you choose what's relevant to your use case.

What you'll get out of this guide: By the end, you'll be able to build a functional
skill in a single sitting. Expect about 15-30 minutes to build and test your first
working skill using the skill-creator.


Chapter 1: Fundamentals
-----------------------

What is a skill?
A skill is a folder containing:
- SKILL.md (required): Instructions in Markdown with YAML frontmatter
- scripts/ (optional): Executable code (Python, Bash, etc.)
- references/ (optional): Documentation loaded as needed
- assets/ (optional): Templates, fonts, icons used in output

Core design principles

Progressive Disclosure
Skills use a three-level system:
- First level (YAML frontmatter): Always loaded in Claude's system prompt.
  Provides just enough information for Claude to know when each skill should
  be used without loading all of it into context.
- Second level (SKILL.md body): Loaded when Claude thinks the skill is
  relevant to the current task. Contains the full instructions and guidance.
- Third level (Linked files): Additional files bundled within the skill directory
  that Claude can choose to navigate and discover only as needed.

This progressive disclosure minimizes token usage while maintaining
specialized expertise.

Composability
Claude can load multiple skills simultaneously. Your skill should work well
alongside others, not assume it's the only capability available.

Portability
Skills work identically across Claude.ai, Claude Code, and API. Create a skill once
and it works across all surfaces without modification, provided the environment
supports any dependencies the skill requires.

For MCP Builders: Skills + Connectors
If you already have a working MCP server, you've done the hard part. Skills are
the knowledge layer on top - capturing the workflows and best practices you
already know, so Claude can apply them consistently.

The kitchen analogy:
- MCP provides the professional kitchen: access to tools, ingredients, and equipment.
- Skills provide the recipes: step-by-step instructions on how to create something valuable.

Together, they enable users to accomplish complex tasks without needing to
figure out every step themselves.

How they work together:

  MCP (Connectivity)                    Skills (Knowledge)
  ------------------------------------  ------------------------------------
  Connects Claude to your service       Teaches Claude how to use your
  (Notion, Asana, Linear, etc.)         service effectively
  Provides real-time data access        Captures workflows and best practices
  and tool invocation
  What Claude can do                    How Claude should do it

Why this matters for your MCP users:

Without skills:
- Users connect your MCP but don't know what to do next
- Support tickets asking "how do I do X with your integration"
- Each conversation starts from scratch
- Inconsistent results because users prompt differently each time
- Users blame your connector when the real issue is workflow guidance

With skills:
- Pre-built workflows activate automatically when needed
- Consistent, reliable tool usage
- Best practices embedded in every interaction
- Lower learning curve for your integration


Chapter 2: Planning and Design
-------------------------------

Start with use cases
Before writing any code, identify 2-3 concrete use cases your skill should enable.

Good use case definition:

  Use Case: Project Sprint Planning
  Trigger: User says "help me plan this sprint" or "create sprint tasks"
  Steps:
    1. Fetch current project status from Linear (via MCP)
    2. Analyze team velocity and capacity
    3. Suggest task prioritization
    4. Create tasks in Linear with proper labels and estimates
  Result: Fully planned sprint with tasks created

Ask yourself:
- What does a user want to accomplish?
- What multi-step workflows does this require?
- Which tools are needed (built-in or MCP)?
- What domain knowledge or best practices should be embedded?

Common skill use case categories

At Anthropic, we've observed three common use cases:

Category 1: Document & Asset Creation
Used for: Creating consistent, high-quality output including documents,
presentations, apps, designs, code, etc.
Real example: frontend-design skill (also see skills for docx, pptx, xlsx, and ppt)

Key techniques:
- Embedded style guides and brand standards
- Template structures for consistent output
- Quality checklists before finalizing
- No external tools required - uses Claude's built-in capabilities

Category 2: Workflow Automation
Used for: Multi-step processes that benefit from consistent methodology,
including coordination across multiple MCP servers.
Real example: skill-creator skill

Key techniques:
- Step-by-step workflow with validation gates
- Templates for common structures
- Built-in review and improvement suggestions
- Iterative refinement loops

Category 3: MCP Enhancement
Used for: Workflow guidance to enhance the tool access an MCP server provides.
Real example: sentry-code-review skill (from Sentry)

Key techniques:
- Coordinates multiple MCP calls in sequence
- Embeds domain expertise
- Provides context users would otherwise need to specify
- Error handling for common MCP issues

Define success criteria
How will you know your skill is working?

Quantitative metrics:
- Skill triggers on 90% of relevant queries
  How to measure: Run 10-20 test queries that should trigger your skill. Track
  how many times it loads automatically vs. requires explicit invocation.
- Completes workflow in X tool calls
  How to measure: Compare the same task with and without the skill enabled.
  Count tool calls and total tokens consumed.
- 0 failed API calls per workflow
  How to measure: Monitor MCP server logs during test runs. Track retry rates
  and error codes.

Qualitative metrics:
- Users don't need to prompt Claude about next steps
  How to assess: During testing, note how often you need to redirect or clarify.
  Ask beta users for feedback.
- Workflows complete without user correction
  How to assess: Run the same request 3-5 times. Compare outputs for
  structural consistency and quality.
- Consistent results across sessions
  How to assess: Can a new user accomplish the task on first try with minimal guidance?

Technical requirements

File structure:
  your-skill-name/
  ├── SKILL.md           # Required - main skill file
  ├── scripts/           # Optional - executable code
  │   ├── process_data.py
  │   └── validate.sh
  ├── references/        # Optional - documentation
  │   ├── api-guide.md
  │   └── examples/
  └── assets/            # Optional - templates, etc.
      └── report-template.md

Critical rules:

SKILL.md naming:
- Must be exactly SKILL.md (case-sensitive)
- No variations accepted (SKILL.MD, skill.md, etc.)

Skill folder naming:
- Use kebab-case: notion-project-setup (correct)
- No spaces: Notion Project Setup (incorrect)
- No underscores: notion_project_setup (incorrect)
- No capitals: NotionProjectSetup (incorrect)

No README.md:
- Don't include README.md inside your skill folder
- All documentation goes in SKILL.md or references/
- Note: when distributing via GitHub, you'll still want a repo-level README
  for human users — see Distribution and Sharing.

YAML frontmatter: The most important part
The YAML frontmatter is how Claude decides whether to load your skill.

Minimal required format:
  ---
  name: your-skill-name
  description: What it does. Use when user asks to [specific phrases].
  ---

Field requirements:

name (required):
- kebab-case only
- No spaces or capitals
- Should match folder name

description (required):
- MUST include BOTH:
  - What the skill does
  - When to use it (trigger conditions)
- Under 1024 characters
- No XML tags (< or >)
- Include specific tasks users might say
- Mention file types if relevant

license (optional):
- Use if making skill open source
- Common: MIT, Apache-2.0

compatibility (optional):
- 1-500 characters
- Indicates environment requirements

metadata (optional):
- Any custom key-value pairs
- Suggested: author, version, mcp-server
- Example:
    metadata:
      author: ProjectHub
      version: 1.0.0
      mcp-server: projecthub

Security restrictions:
Forbidden in frontmatter:
- XML angle brackets (< >)
- Skills with "claude" or "anthropic" in name (reserved)

Writing effective skills

The description field
Structure: [What it does] + [When to use it] + [Key capabilities]

Examples of good descriptions:

  # Good - specific and actionable
  description: Analyzes Figma design files and generates developer handoff
  documentation. Use when user uploads .fig files, asks for "design specs",
  "component documentation", or "design-to-code handoff".

  # Good - includes trigger phrases
  description: Manages Linear project workflows including sprint planning, task
  creation, and status tracking. Use when user mentions "sprint", "Linear tasks",
  "project planning", or asks to "create tickets".

Examples of bad descriptions:

  # Too vague
  description: Helps with projects.

  # Missing triggers
  description: Creates sophisticated multi-page documentation systems.

Writing the main instructions
After the frontmatter, write the actual instructions in Markdown.

Recommended structure:
  ---
  name: your-skill
  description: [.]
  ---

  # Your Skill Name

  # Instructions

  # Step 1: [First Major Step]
  Clear explanation of what happens.

  Example:
    Expected output: [describe what success looks like]

  # Examples
  Example 1: [common scenario]
  User says: "Set up a new marketing campaign"
  Actions:
    1. Fetch existing campaigns via MCP
    2. Create new campaign with provided parameters
  Result: Campaign created with confirmation link

  # Troubleshooting
  Error: [Common error message]
  Cause: [Why it happens]
  Solution: [How to fix]

Best Practices for Instructions:

Be Specific and Actionable:
  Good: Run `python scripts/validate.py --input {filename}` to check data format.
  If validation fails, common issues include:
    - Missing required fields (add them to the CSV)
    - Invalid date formats (use YYYY-MM-DD)

  Bad: Validate the data before proceeding.

Include error handling:
  # Common Issues
  # MCP Connection Failed
  If you see "Connection refused":
    1. Verify MCP server is running: Check Settings > Extensions
    2. Confirm API key is valid
    3. Try reconnecting: Settings > Extensions > [Your Service] > Reconnect

Use progressive disclosure:
Keep SKILL.md focused on core instructions. Move detailed documentation to
`references/` and link to it.


Chapter 3: Testing and Iteration
---------------------------------

Skills can be tested at varying levels of rigor:
- Manual testing in Claude.ai - Run queries directly and observe behavior.
- Scripted testing in Claude Code - Automate test cases for repeatable validation.
- Programmatic testing via skills API - Build evaluation suites that run systematically.

Pro Tip: Iterate on a single task before expanding.
We've found that the most effective skill creators iterate on a single challenging
task until Claude succeeds, then extract the winning approach into a skill.

Recommended Testing Approach

1. Triggering tests
Goal: Ensure your skill loads at the right times.

Test cases:
  Should trigger:
    - "Help me set up a new ProjectHub workspace"
    - "I need to create a project in ProjectHub"
    - "Initialize a ProjectHub project for Q4 planning"

  Should NOT trigger:
    - "What's the weather in San Francisco?"
    - "Help me write Python code"
    - "Create a spreadsheet" (unless ProjectHub skill handles sheets)

2. Functional tests
Goal: Verify the skill produces correct outputs.

Test cases:
  - Valid outputs generated
  - API calls succeed
  - Error handling works
  - Edge cases covered

Example:
  Test: Create project with 5 tasks
  Given: Project name "Q4 Planning", 5 task descriptions
  When: Skill executes workflow
  Then:
    - Project created in ProjectHub
    - 5 tasks created with correct properties
    - All tasks linked to project
    - No API errors

3. Performance comparison
Goal: Prove the skill improves results vs. baseline.

  Without skill:
    - User provides instructions each time
    - 15 back-and-forth messages
    - 3 failed API calls requiring retry
    - 12,000 tokens consumed

  With skill:
    - Automatic workflow execution
    - 2 clarifying questions only
    - 0 failed API calls
    - 6,000 tokens consumed

Using the skill-creator skill
Creating skills:
- Generate skills from natural language descriptions
- Produce properly formatted SKILL.md with frontmatter
- Suggest trigger phrases and structure

Reviewing skills:
- Flag common issues (vague descriptions, missing triggers, structural problems)
- Identify potential over/under-triggering risks
- Suggest test cases based on the skill's stated purpose

Iterative improvement:
- After using your skill and encountering edge cases or failures, bring those
  examples back to skill-creator.

To use:
  "Use the skill-creator skill to help me build a skill for [your use case]"

Iteration based on feedback

Undertriggering signals:
- Skill doesn't load when it should
- Users manually enabling it
- Support questions about when to use it
Solution: Add more detail and nuance to the description

Overtriggering signals:
- Skill loads for irrelevant queries
- Users disabling it
- Confusion about purpose
Solution: Add negative triggers, be more specific

Execution issues:
- Inconsistent results
- API call failures
- User corrections needed
Solution: Improve instructions, add error handling


Chapter 4: Distribution and Sharing
-------------------------------------

Current distribution model (January 2026)

How individual users get skills:
1. Download the skill folder
2. Zip the folder (if needed)
3. Upload to Claude.ai via Settings > Capabilities > Skills
4. Or place in Claude Code skills directory

Organization-level skills:
- Admins can deploy skills workspace-wide (shipped December 18, 2025)
- Automatic updates
- Centralized management

An open standard
Anthropic has published Agent Skills as an open standard. Like MCP, skills
should be portable across tools and platforms.

Using skills via API
Key capabilities:
- /v1/skills endpoint for listing and managing skills
- Add skills to Messages API requests via the `container.skills` parameter
- Version control and management through the Claude Console
- Works with the Claude Agent SDK for building custom agents

When to use skills via the API vs. Claude.ai:

  Use Case                                    Best Surface
  ------------------------------------------  ---------------------------
  End users interacting with skills directly  Claude.ai / Claude Code
  Manual testing and iteration                Claude.ai / Claude Code
  Individual, ad-hoc workflows                Claude.ai / Claude Code
  Applications using skills programmatically  API
  Production deployments at scale             API
  Automated pipelines and agent systems       API

Note: Skills in the API require the Code Execution Tool beta.

For implementation details, see:
- Skills API Quickstart
- Create Custom skills
- Skills in the Agent SDK

Recommended approach today

1. Host on GitHub
   - Public repo for open-source skills
   - Clear README with installation instructions
   - Example usage and screenshots

2. Document in Your MCP Repo
   - Link to skills from MCP documentation
   - Explain the value of using both together
   - Provide quick-start guide

3. Create an Installation Guide
   # Installing the [Your Service] skill
   1. Download the skill:
      - Clone repo: `git clone https://github.com/yourcompany/skills`
      - Or download ZIP from Releases
   2. Install in Claude:
      - Open Claude.ai > Settings > skills
      - Click "Upload skill"
      - Select the skill folder (zipped)
   3. Enable the skill:
      - Toggle on the [Your Service] skill
      - Ensure your MCP server is connected
   4. Test:
      - Ask Claude: "Set up a new project in [Your Service]"

Positioning your skill

Focus on outcomes, not features:
  Good: "The ProjectHub skill enables teams to set up complete project workspaces
  in seconds — including pages, databases, and templates — instead of spending
  30 minutes on manual setup."

  Bad: "The ProjectHub skill is a folder containing YAML frontmatter and Markdown
  instructions that calls our MCP server tools."


Chapter 5: Patterns and Troubleshooting
-----------------------------------------

Choosing your approach: Problem-first vs. tool-first

- Problem-first: "I need to set up a project workspace" → Your skill orchestrates
  the right MCP calls in the right sequence.
- Tool-first: "I have Notion MCP connected" → Your skill teaches Claude the
  optimal workflows and best practices.

Pattern 1: Sequential workflow orchestration
Use when: Your users need multi-step processes in a specific order.

  # Workflow: Onboard New Customer
  # Step 1: Create Account
  Call MCP tool: `create_customer`
  Parameters: name, email, company
  # Step 2: Setup Payment
  Call MCP tool: `setup_payment_method`
  Wait for: payment method verification
  # Step 3: Create Subscription
  Call MCP tool: `create_subscription`
  Parameters: plan_id, customer_id (from Step 1)
  # Step 4: Send Welcome Email
  Call MCP tool: `send_email`
  Template: welcome_email_template

Key techniques:
- Explicit step ordering
- Dependencies between steps
- Validation at each stage
- Rollback instructions for failures

Pattern 2: Multi-MCP coordination
Use when: Workflows span multiple services.

Example: Design-to-development handoff
  # Phase 1: Design Export (Figma MCP)
  1. Export design assets from Figma
  2. Generate design specifications
  3. Create asset manifest
  # Phase 2: Asset Storage (Drive MCP)
  1. Create project folder in Drive
  2. Upload all assets
  3. Generate shareable links
  # Phase 3: Task Creation (Linear MCP)
  1. Create development tasks
  2. Attach asset links to tasks
  3. Assign to engineering team
  # Phase 4: Notification (Slack MCP)
  1. Post handoff summary to #engineering
  2. Include asset links and task references

Key techniques:
- Clear phase separation
- Data passing between MCPs
- Validation before moving to next phase
- Centralized error handling

Pattern 3: Iterative refinement
Use when: Output quality improves with iteration.

  # Iterative Report Creation
  # Initial Draft
  1. Fetch data via MCP
  2. Generate first draft report
  3. Save to temporary file
  # Quality Check
  1. Run validation script: `scripts/check_report.py`
  2. Identify issues: missing sections, inconsistent formatting, data errors
  # Refinement Loop
  1. Address each identified issue
  2. Regenerate affected sections
  3. Re-validate
  4. Repeat until quality threshold met
  # Finalization
  1. Apply final formatting
  2. Generate summary
  3. Save final version

Pattern 4: Context-aware tool selection
Use when: Same outcome, different tools depending on context.

  # Smart File Storage
  # Decision Tree
  1. Check file type and size
  2. Determine best storage location:
     - Large files (>10MB): Use cloud storage MCP
     - Collaborative docs: Use Notion/Docs MCP
     - Code files: Use GitHub MCP
     - Temporary files: Use local storage
  # Execute Storage
  Based on decision:
    - Call appropriate MCP tool
    - Apply service-specific metadata
    - Generate access link
  # Provide Context to User
  Explain why that storage was chosen

Pattern 5: Domain-specific intelligence
Use when: Your skill adds specialized knowledge beyond tool access.

  # Payment Processing with Compliance
  # Before Processing (Compliance Check)
  1. Fetch transaction details via MCP
  2. Apply compliance rules:
     - Check sanctions lists
     - Verify jurisdiction allowances
     - Assess risk level
  3. Document compliance decision
  # Processing
  IF compliance passed:
    - Call payment processing MCP tool
    - Apply appropriate fraud checks
    - Process transaction
  ELSE:
    - Flag for review
    - Create compliance case
  # Audit Trail
    - Log all compliance checks
    - Record processing decisions
    - Generate audit report

Troubleshooting

Skill won't upload:

  Error: "Could not find SKILL.md in uploaded folder"
  Cause: File not named exactly SKILL.md
  Solution: Rename to SKILL.md (case-sensitive)

  Error: "Invalid frontmatter"
  Cause: YAML formatting issue
  Common mistakes:
    - Missing --- delimiters
    - Unclosed quotes
  Correct format:
    ---
    name: my-skill
    description: Does things
    ---

  Error: "Invalid skill name"
  Cause: Name has spaces or capitals
  Correct: name: my-cool-skill

Skill doesn't trigger:

  Symptom: Skill never loads automatically
  Fix: Revise your description field.
  Quick checklist:
    - Is it too generic?
    - Does it include trigger phrases users would actually say?
    - Does it mention relevant file types if applicable?
  Debugging: Ask Claude: "When would you use the [skill name] skill?"

Skill triggers too often:

  Solutions:
  1. Add negative triggers:
     description: Advanced data analysis for CSV files. Use for statistical
     modeling, regression, clustering. Do NOT use for simple data exploration.
  2. Be more specific in scope
  3. Clarify scope with specific service/context

MCP connection issues:

  Symptom: Skill loads but MCP calls fail
  Checklist:
  1. Verify MCP server is connected (Settings > Extensions)
  2. Check authentication (API keys, OAuth tokens)
  3. Test MCP independently without skill
  4. Verify tool names (case-sensitive)

Instructions not followed:

  Common causes:
  1. Instructions too verbose — keep concise, use bullet points
  2. Instructions buried — put critical instructions at the top
  3. Ambiguous language — use explicit, precise language
  4. Model "laziness" — add explicit encouragement:
       # Performance Notes
       - Take your time to do this thoroughly
       - Quality is more important than speed
       - Do not skip validation steps

Large context issues:

  Symptom: Skill seems slow or responses degraded
  Solutions:
  1. Optimize SKILL.md size (move detailed docs to references/, keep under 5,000 words)
  2. Reduce enabled skills (evaluate if you have more than 20-50 simultaneously)
  3. Consider skill "packs" for related capabilities


Chapter 6: Resources and References
--------------------------------------

Official Documentation

Anthropic Resources:
- Best Practices Guide
- Skills Documentation
- API Reference
- MCP Documentation

Blog Posts:
- Introducing Agent Skills
- Engineering Blog: Equipping Agents for the Real World
- Skills Explained
- How to Create Skills for Claude
- Building Skills for Claude Code
- Improving Frontend Design through Skills

Example skills

Public skills repository:
- GitHub: anthropics/skills
- Contains Anthropic-created skills you can customize

Tools and Utilities

skill-creator skill:
- Built into Claude.ai and available for Claude Code
- Can generate skills from descriptions
- Reviews and provides recommendations
- Use: "Help me build a skill using skill-creator"

Getting Support

For Technical Questions:
- Community forums at the Claude Developers Discord

For Bug Reports:
- GitHub Issues: anthropics/skills/issues
- Include: Skill name, error message, steps to reproduce


Reference A: Quick Checklist
------------------------------

Before you start:
  [ ] Identified 2-3 concrete use cases
  [ ] Tools identified (built-in or MCP)
  [ ] Reviewed this guide and example skills
  [ ] Planned folder structure

During development:
  [ ] Folder named in kebab-case
  [ ] SKILL.md file exists (exact spelling)
  [ ] YAML frontmatter has --- delimiters
  [ ] name field: kebab-case, no spaces, no capitals
  [ ] description includes WHAT and WHEN
  [ ] No XML tags (< >) anywhere
  [ ] Instructions are clear and actionable
  [ ] Error handling included
  [ ] Examples provided
  [ ] References clearly linked

Before upload:
  [ ] Tested triggering on obvious tasks
  [ ] Tested triggering on paraphrased requests
  [ ] Verified doesn't trigger on unrelated topics
  [ ] Functional tests pass
  [ ] Tool integration works (if applicable)
  [ ] Compressed as .zip file

After upload:
  [ ] Test in real conversations
  [ ] Monitor for under/over-triggering
  [ ] Collect user feedback
  [ ] Iterate on description and instructions
  [ ] Update version in metadata


Reference B: YAML Frontmatter
-------------------------------

Required fields:
  ---
  name: skill-name-in-kebab-case
  description: What it does and when to use it. Include specific trigger phrases.
  ---

All optional fields:
  name: skill-name
  description: [required description]
  license: MIT
  allowed-tools: "Bash(python:*) Bash(npm:*) WebFetch"
  metadata:
    author: Company Name
    version: 1.0.0
    mcp-server: server-name
    category: productivity
    tags: [project-management, automation]
    documentation: https://example.com/docs
    support: support@example.com

Security notes:
  Allowed:
    - Any standard YAML types (strings, numbers, booleans, lists, objects)
    - Custom metadata fields
    - Long descriptions (up to 1024 characters)
  Forbidden:
    - XML angle brackets (< >) - security restriction
    - Code execution in YAML
    - Skills named with "claude" or "anthropic" prefix (reserved)


Reference C: Complete Skill Examples
--------------------------------------

For full, production-ready skills demonstrating the patterns in this guide:
- Document Skills - PDF, DOCX, PPTX, XLSX creation
- Example Skills - Various workflow patterns
- Partner Skills Directory - Skills from Asana, Atlassian, Canva, Figma,
  Sentry, Zapier, and more

These repositories stay up-to-date and include additional examples beyond
what's covered here.