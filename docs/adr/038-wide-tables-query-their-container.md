# ADR-038: Wide tables query their available content width

## Status

Accepted.

**Amends** ADR-037 for the targets and storage list breakpoint.

## Context

ADR-037 changed the targets and storage tables into labelled cards at a
1399-pixel viewport breakpoint. The console also has a fixed sidebar, page
padding and a maximum content width, so viewport width does not say how much
space the table actually has. At a 1440-pixel viewport the content column was
only about 1090 pixels while the targets table needed 1540 pixels. The table
therefore returned to its wide layout and hid most actions behind horizontal
scrolling.

## Decision

The targets and storage lists each place their existing table wrapper inside
a named inline-size container. The table declares its measured 1540-pixel
minimum. When the container is narrower than that minimum, a container query
renders the rows as labelled cards and wraps their actions; at or above it the
dense table remains.

The phone card rules remain as the narrower presentation layer, and header
action groups keep their viewport breakpoint because the header itself is not
the table's overflow boundary. No JavaScript measures layout or mutates the
DOM.

## Consequences

- Sidebar width, page padding and embedded layouts can no longer make a wide
  table appear before it fits.
- Both resource lists use cards at every width currently allowed by the
  console's 1240-pixel page maximum. A future wider shell automatically gains
  the table view once its content container reaches 1540 pixels.
- The server-rendered table semantics and mobile behaviour are unchanged.
