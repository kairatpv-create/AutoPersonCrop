from pathlib import Path
import sys

root=Path(sys.argv[1])
p=root/'app/src/main/java/kz/kairat/organizer/OrganizerUi.kt'
s=p.read_text()

def section(text,start_marker,end_marker):
    a=text.index(start_marker); b=text.index(end_marker,a)
    return a,b,text[a:b]

def replace_section(text,start_marker,end_marker,transform):
    a,b,part=section(text,start_marker,end_marker)
    return text[:a]+transform(part)+text[b:]

# Restore the independent Project screen's own tab state after the generic
# Finance substitutions in apply.py.
def fix_project_tabs(part):
    part=part.replace('TabRow(selectedTabIndex=selectedTab){','TabRow(selectedTabIndex=tab){')
    part=part.replace('Tab(selected=selectedTab==i,onClick={onTabSelected(i)},','Tab(selected=tab==i,onClick={tab=i},')
    part=part.replace('when(selectedTab){','when(tab){')
    return part
s=replace_section(s,'@Composable private fun ProjectDetailScreen','@Composable private fun ProjectOverviewTab',fix_project_tabs)

# Project finance intentionally keeps its existing income/expense chooser.
def fix_project_money(part):
    if 'var type by remember{mutableStateOf("expense")}' not in part:
        part=part.replace('    var amount by remember{mutableStateOf("")};var cat by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}\n',
                          '    var type by remember{mutableStateOf("expense")};var amount by remember{mutableStateOf("")};var cat by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}\n',1)
    return part
s=replace_section(s,'@Composable private fun AddProjectMoneyDialog','@Composable private fun ProjectFilesTab',fix_project_money)

# Main Finance screen uses the parent-owned selected tab, so Create can be
# income-only or expense-only depending on the active tab.
def fix_money_tabs(part):
    part=part.replace('TabRow(selectedTabIndex=tab,containerColor=Color.Transparent,divider={}){','TabRow(selectedTabIndex=selectedTab,containerColor=Color.Transparent,divider={}){')
    part=part.replace('Tab(selected=tab==i,onClick={tab=i},','Tab(selected=selectedTab==i,onClick={onTabSelected(i)},')
    part=part.replace('when(tab){','when(selectedTab){')
    return part
s=replace_section(s,'@Composable private fun MoneyScreen','@Composable private fun MoneyOverview',fix_money_tabs)

p.write_text(s)
