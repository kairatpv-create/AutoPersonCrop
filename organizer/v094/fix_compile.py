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
    new=transform(part)
    return text[:a]+new+text[b:]

# The v0.9.4 patch intentionally changes the Finance tabs, but some generic tab
# substitutions can also touch ProjectDetailScreen. Restore project-local tab state.
def fix_project_tabs(part):
    part=part.replace('TabRow(selectedTabIndex=selectedTab){','TabRow(selectedTabIndex=tab){')
    part=part.replace('Tab(selected=selectedTab==i,onClick={onTabSelected(i)},','Tab(selected=tab==i,onClick={tab=i},')
    part=part.replace('when(selectedTab){','when(tab){')
    return part
s=replace_section(s,'@Composable private fun ProjectDetailScreen','@Composable private fun ProjectOverviewTab',fix_project_tabs)

# Project finance keeps its existing mixed income/expense chooser. Main Finance
# uses the new fixed-type creation dialogs, so restore the project dialog's type state.
def fix_project_money(part):
    if 'var type by remember{mutableStateOf("expense")}' not in part:
        part=part.replace('    var amount by remember{mutableStateOf("")};var cat by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}\n',
                          '    var type by remember{mutableStateOf("expense")};var amount by remember{mutableStateOf("")};var cat by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}\n',1)
    return part
s=replace_section(s,'@Composable private fun AddProjectMoneyDialog','@Composable private fun ProjectFilesTab',fix_project_money)

# Apply the controlled tab substitutions only inside the main Finance screen.
def fix_money_tabs(part):
    part=part.replace('TabRow(selectedTabIndex=tab,containerColor=Color.Transparent,divider={}){','TabRow(selectedTabIndex=selectedTab,containerColor=Color.Transparent,divider={}){')
    part=part.replace('Tab(selected=tab==i,onClick={tab=i},','Tab(selected=selectedTab==i,onClick={onTabSelected(i)},')
    part=part.replace('when(tab){','when(selectedTab){')
    return part
s=replace_section(s,'@Composable private fun MoneyScreen','@Composable private fun MoneyOverview',fix_money_tabs)

p.write_text(s)

# Compile-safety gates for the exact regressions fixed here.
s=p.read_text()
_,_,project=section(s,'@Composable private fun ProjectDetailScreen','@Composable private fun ProjectOverviewTab')
assert 'TabRow(selectedTabIndex=tab)' in project
assert 'Tab(selected=tab==i,onClick={tab=i}' in project
assert 'when(tab){' in project
assert 'selectedTab' not in project and 'onTabSelected' not in project
_,_,project_money=section(s,'@Composable private fun AddProjectMoneyDialog','@Composable private fun ProjectFilesTab')
assert 'var type by remember{mutableStateOf("expense")}' in project_money
_,_,money=section(s,'@Composable private fun MoneyScreen','@Composable private fun MoneyOverview')
assert 'TabRow(selectedTabIndex=selectedTab' in money
assert 'Tab(selected=selectedTab==i,onClick={onTabSelected(i)}' in money
assert 'when(selectedTab){' in money
assert 'var tab by' not in money
