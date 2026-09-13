from html.parser import HTMLParser
from pathlib import Path
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import (
    BaseDocTemplate, Frame, PageTemplate, Paragraph, Preformatted,
    Spacer, Table, TableStyle, PageBreak, KeepTogether,
)

SOURCE = Path(__file__).with_name('GUIDE-RECETTE-SIGEP.html')
OUTPUT = Path(__file__).with_name('GUIDE-RECETTE-SIGEP.pdf')

class GuideParser(HTMLParser):
    BLOCKS = {'h1', 'h2', 'h3', 'h4', 'p', 'li', 'pre', 'div'}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.story = []
        self.tag_stack = []
        self.text = []
        self.current_tag = None
        self.in_table = False
        self.table_rows = []
        self.table_row = None
        self.table_cell = None
        self.skip_depth = 0

    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        if tag in ('style', 'script', 'title', 'head'):
            self.skip_depth += 1
            return
        if self.skip_depth:
            return
        if tag == 'table':
            self.in_table = True
            self.table_rows = []
        elif self.in_table and tag == 'tr':
            self.table_row = []
        elif self.in_table and tag in ('th', 'td'):
            self.table_cell = []
        elif tag in self.BLOCKS:
            if self.current_tag is not None:
                self.finish_block()
            self.current_tag = tag
            self.text = []
        self.tag_stack.append(tag)

    def handle_endtag(self, tag):
        tag = tag.lower()
        if self.skip_depth:
            self.skip_depth -= 1
            return
        if self.in_table and tag in ('th', 'td') and self.table_cell is not None:
            self.table_row.append(' '.join(''.join(self.table_cell).split()))
            self.table_cell = None
        elif self.in_table and tag == 'tr' and self.table_row is not None:
            self.table_rows.append(self.table_row)
            self.table_row = None
        elif tag == 'table':
            self.finish_table()
        elif tag == self.current_tag:
            self.finish_block()
        if self.tag_stack:
            self.tag_stack.pop()

    def handle_data(self, data):
        if self.skip_depth:
            return
        if self.in_table and self.table_cell is not None:
            self.table_cell.append(data)
        elif self.current_tag is not None:
            self.text.append(data)

    def finish_block(self):
        value = ' '.join(''.join(self.text).split())
        tag = self.current_tag
        self.current_tag = None
        self.text = []
        if not value:
            return
        if tag == 'pre':
            self.story.append(Preformatted(value, styles['Code']))
            self.story.append(Spacer(1, 3 * mm))
        elif tag == 'li':
            self.story.append(Paragraph('&#9633; ' + escape(value), styles['Body']))
        elif tag == 'h1':
            self.story.append(Paragraph(escape(value), styles['Title']))
        elif tag == 'h2':
            self.story.append(Paragraph(escape(value), styles['H2']))
        elif tag == 'h3':
            self.story.append(Paragraph(escape(value), styles['H3']))
        elif tag == 'h4':
            self.story.append(Paragraph(escape(value), styles['H4']))
        else:
            self.story.append(Paragraph(escape(value), styles['Body']))
        self.story.append(Spacer(1, 1.5 * mm))

    def finish_table(self):
        self.in_table = False
        if not self.table_rows:
            return
        rows = [[Paragraph(escape(cell), styles['Table']) for cell in row] for row in self.table_rows]
        columns = max(len(row) for row in rows)
        for row in rows:
            row.extend([''] * (columns - len(row)))
        table = Table(rows, repeatRows=1, colWidths=[(180 / columns) * mm] * columns)
        table.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#174a72')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
            ('GRID', (0, 0), (-1, -1), 0.35, colors.HexColor('#d9e0e8')),
            ('VALIGN', (0, 0), (-1, -1), 'TOP'),
            ('BACKGROUND', (0, 1), (-1, -1), colors.HexColor('#f7fafb')),
            ('LEFTPADDING', (0, 0), (-1, -1), 4),
            ('RIGHTPADDING', (0, 0), (-1, -1), 4),
            ('TOPPADDING', (0, 0), (-1, -1), 4),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
        ]))
        self.story.append(table)
        self.story.append(Spacer(1, 3 * mm))

styles0 = getSampleStyleSheet()
styles = {
    'Title': ParagraphStyle('GuideTitle', parent=styles0['Title'], fontName='Helvetica-Bold', fontSize=25, leading=29, textColor=colors.HexColor('#174a72'), alignment=TA_CENTER, spaceAfter=8),
    'H2': ParagraphStyle('GuideH2', parent=styles0['Heading2'], fontName='Helvetica-Bold', fontSize=16, leading=19, textColor=colors.HexColor('#174a72'), spaceBefore=10, spaceAfter=5, keepWithNext=True),
    'H3': ParagraphStyle('GuideH3', parent=styles0['Heading3'], fontName='Helvetica-Bold', fontSize=12, leading=15, textColor=colors.HexColor('#087f8c'), spaceBefore=7, spaceAfter=3, keepWithNext=True),
    'H4': ParagraphStyle('GuideH4', parent=styles0['Heading4'], fontName='Helvetica-Bold', fontSize=10.5, leading=13, textColor=colors.HexColor('#087f8c'), spaceBefore=5, spaceAfter=2, keepWithNext=True),
    'Body': ParagraphStyle('GuideBody', parent=styles0['BodyText'], fontName='Helvetica', fontSize=8.8, leading=11.5, textColor=colors.HexColor('#1b2430'), spaceAfter=2),
    'Table': ParagraphStyle('GuideTable', parent=styles0['BodyText'], fontName='Helvetica', fontSize=7.3, leading=9, textColor=colors.HexColor('#1b2430')),
    'Code': ParagraphStyle('GuideCode', parent=styles0['Code'], fontName='Courier', fontSize=7.2, leading=9, textColor=colors.HexColor('#17212b'), backColor=colors.HexColor('#f1f4f7'), borderPadding=5, spaceAfter=3),
}

class GuideDocTemplate(BaseDocTemplate):
    def __init__(self, filename):
        super().__init__(filename, pagesize=A4, rightMargin=15*mm, leftMargin=15*mm, topMargin=15*mm, bottomMargin=16*mm)
        frame = Frame(self.leftMargin, self.bottomMargin, self.width, self.height, id='normal')
        self.addPageTemplates([PageTemplate(id='guide', frames=frame, onPage=self.footer)])

    def footer(self, canvas, doc):
        canvas.saveState()
        canvas.setFont('Helvetica', 7.5)
        canvas.setFillColor(colors.HexColor('#687386'))
        canvas.drawString(15*mm, 9*mm, 'SIGEP - Guide de recette')
        canvas.drawRightString(A4[0] - 15*mm, 9*mm, f'Page {doc.page}')
        canvas.restoreState()

html = SOURCE.read_text(encoding='utf-8')
parser = GuideParser()
parser.feed(html)
doc = GuideDocTemplate(str(OUTPUT))
doc.build(parser.story)
print(f'Créé: {OUTPUT} ({OUTPUT.stat().st_size} octets)')
