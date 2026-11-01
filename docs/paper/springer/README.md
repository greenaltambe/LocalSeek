# LocalSeek paper: Springer Nature version (Personal and Ubiquitous Computing)

This folder is the **master copy** of the paper from 2026-11-01. `docs/paper/latex/` (article class) is the older layout; do not edit it any more.

- `main.tex`: manuscript in the Springer Nature LaTeX template v3.1 (`sn-jnl.cls`, numbered references, `sn-basic.bst`).
- `figures/`: all charts, drawn at final size in one style (8 pt Arial-compatible, black and grey, marker shapes for grayscale) by `python3 scripts/make_figures_springer.py` (run from this folder; reads the committed result files).
- Diagrams: `python3 scripts/make_flows.py` draws Figs 1, 3, 4 and 9 (`drawio/*.pdf`) and writes an editable draw.io twin of each (`drawio/*.drawio`, open with File > Open in draw.io). Fig 2 (architecture) is `diagrams/arch.mmd`, a Mermaid source (draw.io: Arrange > Insert > Advanced > Mermaid), rendered with `mmdc -i diagrams/arch.mmd -o drawio/arch.pdf -c mermaid-config.json --pdfFit -b white -w 3000`. All line art is black on white with no shading.
- `sub/`: the flat submission package (main.tex, Fig1.pdf to Fig10.pdf, class, style, bib, bbl), rebuilt by `scripts/make_submission.py`.

## Build
```
cd docs/paper/springer
latexmk -pdf main.tex
python3 scripts/make_submission.py      # refresh sub/
# diagrams, after editing diagrams/*.mmd:
for f in diagrams/*.mmd; do n=$(basename $f .mmd); mmdc -i $f -o drawio/$n.pdf -c mermaid-config.json --pdfFit -b white -w 3000; done
```

## Journal requirements and where they are met
| Requirement (journal guidelines) | Status |
|---|---|
| Abstract 150-250 words, no undefined abbreviations | 247 words |
| 4-6 keywords | 6 |
| Numbered citations [n], Springer reference style | `sn-basic`, `Numbered` |
| Title page: names, affiliation (city, country), corresponding author with email | done |
| Declarations: Funding, Competing interests, Ethics/consent, Data and Code availability | done (Declarations section) |
| Generative-AI use documented in the methods | Section 3, "Use of generative AI" |
| Line art black and white, Arial/Helvetica lettering about 8 pt or larger | diagrams redrawn in black and white with Arial; charts readable in grayscale |
| Figure captions in the text, "Fig." numbering, files named Fig1, Fig2, ... | `sub/` |
| Decimal headings, at most three levels | done |

## The owner must still do these
1. **Ethics statement:** add the approving committee's name and approval number, if there is one, to "Ethics approval and consent to participate" in `main.tex`.
2. **Author contributions and competing interests:** enter them in the submission system (the journal publishes only what is entered there). CRediT roles are recommended.
3. **ORCID iDs:** add them for each author in the submission system, if available.
4. **Cover letter:** a short letter to the editor (title, one-paragraph summary, why it fits Personal and Ubiquitous Computing, a statement that it is not under review elsewhere).
5. **Proofread the PDF once**, especially names and the acknowledgement.
