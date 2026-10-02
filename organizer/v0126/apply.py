from pathlib import Path
import sys

root=Path(sys.argv[1])
base=root/'app/src/main/java/kz/kairat/organizer'
ui=base/'OrganizerUi.kt'
theme=base/'Theme.kt'
cloud=base/'CloudSync.kt'
gradle=root/'app/build.gradle.kts'

# ---------------------------------------------------------------------------
# Theme rule for v0.9.36:
# - all app/interface typography follows BUTTON text scale;
# - LocalContentTextScale is reserved for text authored/entered by the user.
# ---------------------------------------------------------------------------
t=theme.read_text()
old='''    val contentSize=(14f*contentTextScale).sp
    val contentTitle=(15f*contentTextScale).sp
    val buttonSize=(15f*buttonTextScale).sp
    val type=Typography(
        displayLarge=TextStyle(fontSize=contentTitle,fontWeight=strong),
        displayMedium=TextStyle(fontSize=contentTitle,fontWeight=strong),
        displaySmall=TextStyle(fontSize=contentTitle,fontWeight=strong),
        headlineLarge=TextStyle(fontSize=contentTitle,fontWeight=strong),
        headlineMedium=TextStyle(fontSize=contentTitle,fontWeight=strong),
        headlineSmall=TextStyle(fontSize=contentTitle,fontWeight=strong),
        titleLarge=TextStyle(fontSize=contentTitle,fontWeight=strong),
        titleMedium=TextStyle(fontSize=contentTitle,fontWeight=strong),
        titleSmall=TextStyle(fontSize=contentTitle,fontWeight=strong),
        bodyLarge=TextStyle(fontSize=contentSize,fontWeight=weight),
        bodyMedium=TextStyle(fontSize=contentSize,fontWeight=weight),
        bodySmall=TextStyle(fontSize=contentSize,fontWeight=weight),
        labelLarge=TextStyle(fontSize=buttonSize,fontWeight=strong),
        labelMedium=TextStyle(fontSize=buttonSize,fontWeight=strong),
        labelSmall=TextStyle(fontSize=contentSize,fontWeight=weight)
    )
'''
new='''    val uiBodySize=(14f*buttonTextScale).sp
    val uiTitleSize=(15f*buttonTextScale).sp
    val buttonSize=(15f*buttonTextScale).sp
    val type=Typography(
        displayLarge=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        displayMedium=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        displaySmall=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        headlineLarge=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        headlineMedium=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        headlineSmall=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        titleLarge=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        titleMedium=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        titleSmall=TextStyle(fontSize=uiTitleSize,fontWeight=strong),
        bodyLarge=TextStyle(fontSize=uiBodySize,fontWeight=weight),
        bodyMedium=TextStyle(fontSize=uiBodySize,fontWeight=weight),
        bodySmall=TextStyle(fontSize=uiBodySize,fontWeight=weight),
        labelLarge=TextStyle(fontSize=buttonSize,fontWeight=strong),
        labelMedium=TextStyle(fontSize=buttonSize,fontWeight=strong),
        labelSmall=TextStyle(fontSize=uiBodySize,fontWeight=weight)
    )
'''
assert old in t
t=t.replace(old,new,1)
append='''

/** Text authored or entered by the user. UI/service labels must use MaterialTheme typography instead. */
@Composable fun userTextStyle(sizeSp:Float=14f,strong:Boolean=false):TextStyle{
    val scale=LocalContentTextScale.current.coerceIn(.85f,1.45f)
    val base=if(strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    return base.copy(fontSize=(sizeSp*scale).sp)
}
'''
assert 'fun userTextStyle(' not in t
t=t.rstrip()+append+'\n'
theme.write_text(t)

# ---------------------------------------------------------------------------
# UI: settings themselves are interface and therefore follow button scale.
# ---------------------------------------------------------------------------
u=ui.read_text()
u=u.replace('val compactSettings=kotlin.math.max(fontScale,buttonScale)<=1.16f','val compactSettings=buttonScale<=1.16f',1)
u=u.replace('val onePageSettings=fontScale<=.90f&&buttonScale<=.90f','val onePageSettings=buttonScale<=.90f',1)
u=u.replace('val scale=LocalContentTextScale.current\n    val compact=scale<=1.16f','val scale=LocalButtonTextScale.current\n    val compact=scale<=1.16f',1)

# Notes: title/body are user-authored.
u=u.replace('Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold))','Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=userTextStyle(15f,true))',1)
u=u.replace('if(n.body.isNotBlank())Text(n.body,maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)','if(n.body.isNotBlank())Text(n.body,maxLines=3,overflow=TextOverflow.Ellipsis,style=userTextStyle())',1)
u=u.replace('title={Text(note.title.ifBlank{AppStrings.t(lang,"note")},maxLines=1)}','title={Text(note.title.ifBlank{AppStrings.t(lang,"note")},maxLines=1,style=userTextStyle(15f,true))}',1)
u=u.replace('item{Text(note.body)}','item{Text(note.body,style=userTextStyle())}',1)

# Note editor: typed title and body use content scale; labels/placeholders remain UI scale.
old='''item{OutlinedTextField(body,{body=capitalizeSentences(it)},Modifier.fillMaxWidth().heightIn(min=220.dp),label={Text(AppStrings.t(lang,"note"))},placeholder={Text(AppStrings.t(lang,"note_hint"))},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences))}'''
new='''item{OutlinedTextField(body,{body=capitalizeSentences(it)},Modifier.fillMaxWidth().heightIn(min=220.dp),label={Text(AppStrings.t(lang,"note"))},placeholder={Text(AppStrings.t(lang,"note_hint"))},textStyle=userTextStyle(),keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences))}'''
assert old in u
u=u.replace(old,new,1)

# Projects list/details: names, descriptions, journal, linked note content and stage titles are user-authored.
u=u.replace('Text(p.name,style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)','Text(p.name,style=userTextStyle(15f,true),modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)',1)
u=u.replace('if(p.description.isNotBlank())Text(p.description,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)','if(p.description.isNotBlank())Text(p.description,maxLines=2,overflow=TextOverflow.Ellipsis,style=userTextStyle())',1)
u=u.replace('Scaffold(topBar={TopAppBar(title={Text(project.name,maxLines=1,overflow=TextOverflow.Ellipsis)}','Scaffold(topBar={TopAppBar(title={Text(project.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=userTextStyle(15f,true))}',1)
u=u.replace('if(project.description.isNotBlank())Text(project.description,style=MaterialTheme.typography.bodyMedium)','if(project.description.isNotBlank())Text(project.description,style=userTextStyle())',1)
u=u.replace('Column(Modifier.weight(1f)){Text(j.text);Text(formatTime(j.createdAt,lang),style=MaterialTheme.typography.bodySmall','Column(Modifier.weight(1f)){Text(j.text,style=userTextStyle());Text(formatTime(j.createdAt,lang),style=MaterialTheme.typography.bodySmall',1)
u=u.replace('Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=MaterialTheme.typography.titleSmall);if(n.body.isNotBlank())Text(n.body,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)','Text(n.title.ifBlank{AppStrings.t(lang,"note")},style=userTextStyle(15f,true));if(n.body.isNotBlank())Text(n.body,maxLines=2,overflow=TextOverflow.Ellipsis,style=userTextStyle())',1)
old_stage='''Text(st.title,style=if(st.done)MaterialTheme.typography.bodyMedium.copy(textDecoration=TextDecoration.LineThrough) else MaterialTheme.typography.bodyMedium)'''
new_stage='''Text(st.title,style=if(st.done)userTextStyle().copy(textDecoration=TextDecoration.LineThrough) else userTextStyle())'''
assert old_stage in u
u=u.replace(old_stage,new_stage,1)

# Project info: mark user-entered value rows explicitly; dates remain interface text.
old='''@Composable private fun ProjectInfoRow(label:String,value:String){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(.42f));Text(value,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(.58f))}}'''
new='''@Composable private fun ProjectInfoRow(label:String,value:String,userValue:Boolean=false){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(.42f));Text(value,style=if(userValue)userTextStyle() else MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(.58f))}}'''
assert old in u
u=u.replace(old,new,1)
for key in ['budget','client','address','contact']:
    if key=='budget':
        u=u.replace('ProjectInfoRow(AppStrings.t(lang,"budget"),"%,.0f ₸".format(project.budget))','ProjectInfoRow(AppStrings.t(lang,"budget"),"%,.0f ₸".format(project.budget),true)',1)
    else:
        u=u.replace(f'ProjectInfoRow(AppStrings.t(lang,"{key}"),project.{key})',f'ProjectInfoRow(AppStrings.t(lang,"{key}"),project.{key},true)',1)

# Finance entry cards are user-authored values/category/comment. Summary cards and computed balance remain UI scale.
u=u.replace('Text("%,.0f ₸".format(m.amount),style=MaterialTheme.typography.titleSmall,color=amountColor,modifier=Modifier.weight(1f),maxLines=1)','Text("%,.0f ₸".format(m.amount),style=userTextStyle(15f,true),color=amountColor,modifier=Modifier.weight(1f),maxLines=1)',1)
u=u.replace('Text(label,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)','Text(label,style=userTextStyle(),maxLines=2,overflow=TextOverflow.Ellipsis)',1)
u=u.replace('if(m.category.isNotBlank()&&m.note.isNotBlank())Text(m.note,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)','if(m.category.isNotBlank()&&m.note.isNotBlank())Text(m.note,style=userTextStyle(),maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)',1)

# Tasks/reminders: user supplied titles/details follow content scale.
u=u.replace('Text(t.title,style=MaterialTheme.typography.titleMedium);if(t.details.isNotBlank())Text(t.details)','Text(t.title,style=userTextStyle(15f,true));if(t.details.isNotBlank())Text(t.details,style=userTextStyle())',1)
u=u.replace('title={Text(task.title)}','title={Text(task.title,style=userTextStyle(15f,true))}',1)
u=u.replace('if(task.details.isNotBlank())item{Text(task.details)}','if(task.details.isNotBlank())item{Text(task.details,style=userTextStyle())}',1)
u=u.replace('Text(r.title.ifBlank{AppStrings.t(lang,"reminders")},style=MaterialTheme.typography.titleMedium)','Text(r.title.ifBlank{AppStrings.t(lang,"reminders")},style=userTextStyle(15f,true))',1)
u=u.replace('if(r.details.isNotBlank())Text(r.details,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)','if(r.details.isNotBlank())Text(r.details,maxLines=2,overflow=TextOverflow.Ellipsis,style=userTextStyle())',1)

# SuggestionTextField is always an editable user-value field. Keep its label as interface text.
old='''            label={Text(label)},
            singleLine=true,
            keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences),'''
new='''            label={Text(label)},
            singleLine=true,
            textStyle=userTextStyle(),
            keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences),'''
assert old in u
u=u.replace(old,new,1)

# Common direct editable fields: add content-scale textStyle while labels remain UI scale.
replacements={
'OutlinedTextField(amount,{amount=formatAmountInput(it)},label={Text(AppStrings.t(lang,"amount"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)':
'OutlinedTextField(amount,{amount=formatAmountInput(it)},label={Text(AppStrings.t(lang,"amount"))},textStyle=userTextStyle(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)',
'OutlinedTextField(note,{note=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"comment"))})':
'OutlinedTextField(note,{note=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"comment"))},textStyle=userTextStyle())',
'OutlinedTextField(name,{name=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"title"))},singleLine=true,modifier=Modifier.fillMaxWidth())':
'OutlinedTextField(name,{name=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"title"))},textStyle=userTextStyle(),singleLine=true,modifier=Modifier.fillMaxWidth())',
'OutlinedTextField(description,{description=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"details"))},minLines=3,modifier=Modifier.fillMaxWidth())':
'OutlinedTextField(description,{description=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"details"))},textStyle=userTextStyle(),minLines=3,modifier=Modifier.fillMaxWidth())',
'OutlinedTextField(budget,{budget=formatAmountInput(it)},label={Text(AppStrings.t(lang,"budget"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())':
'OutlinedTextField(budget,{budget=formatAmountInput(it)},label={Text(AppStrings.t(lang,"budget"))},textStyle=userTextStyle(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())',
'OutlinedTextField(client,{client=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"client"))},singleLine=true,modifier=Modifier.fillMaxWidth())':
'OutlinedTextField(client,{client=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"client"))},textStyle=userTextStyle(),singleLine=true,modifier=Modifier.fillMaxWidth())',
'OutlinedTextField(address,{address=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"address"))},singleLine=true,modifier=Modifier.fillMaxWidth())':
'OutlinedTextField(address,{address=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"address"))},textStyle=userTextStyle(),singleLine=true,modifier=Modifier.fillMaxWidth())',
'OutlinedTextField(contact,{contact=it},label={Text(AppStrings.t(lang,"contact"))},singleLine=true,modifier=Modifier.fillMaxWidth())':
'OutlinedTextField(contact,{contact=it},label={Text(AppStrings.t(lang,"contact"))},textStyle=userTextStyle(),singleLine=true,modifier=Modifier.fillMaxWidth())',
'OutlinedTextField(title,{title=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"title"))},singleLine=true)':
'OutlinedTextField(title,{title=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"title"))},textStyle=userTextStyle(),singleLine=true)',
'OutlinedTextField(text,{text=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"note"))},minLines=5,modifier=Modifier.fillMaxWidth())':
'OutlinedTextField(text,{text=capitalizeSentences(it)},label={Text(AppStrings.t(lang,"note"))},textStyle=userTextStyle(),minLines=5,modifier=Modifier.fillMaxWidth())'
}
for old,new in replacements.items():
    if old in u:
        u=u.replace(old,new)

# Multi-line AddProject/AddMoney fields from newer full-screen forms.
u=u.replace('''                    label={Text(AppStrings.t(lang,"title"))},
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)''','''                    label={Text(AppStrings.t(lang,"title"))},
                    textStyle=userTextStyle(),
                    singleLine=true,
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)''')
u=u.replace('''                    label={Text(AppStrings.t(lang,"details"))},
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)''','''                    label={Text(AppStrings.t(lang,"details"))},
                    textStyle=userTextStyle(),
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)''')
u=u.replace('''                    label={Text(AppStrings.t(lang,"amount"))},
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),''','''                    label={Text(AppStrings.t(lang,"amount"))},
                    textStyle=userTextStyle(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),''')
u=u.replace('''                    label={Text(AppStrings.t(lang,"comment"))},
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)''','''                    label={Text(AppStrings.t(lang,"comment"))},
                    textStyle=userTextStyle(),
                    keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences)''')

ui.write_text(u)

# Account fields: email/password are user-entered values. Labels/status stay UI scale.
if cloud.exists():
    c=cloud.read_text()
    c=c.replace('OutlinedTextField(email,{email=it},Modifier.weight(1f),label={Text(syncText(lang,"email"))},singleLine=true)',
                'OutlinedTextField(email,{email=it},Modifier.weight(1f),label={Text(syncText(lang,"email"))},textStyle=userTextStyle(),singleLine=true)')
    c=c.replace('OutlinedTextField(password,{password=it},Modifier.weight(1f),label={Text(syncText(lang,"password"))},singleLine=true,visualTransformation=PasswordVisualTransformation())',
                'OutlinedTextField(password,{password=it},Modifier.weight(1f),label={Text(syncText(lang,"password"))},textStyle=userTextStyle(),singleLine=true,visualTransformation=PasswordVisualTransformation())')
    c=c.replace('OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"email"))},singleLine=true)',
                'OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"email"))},textStyle=userTextStyle(),singleLine=true)')
    c=c.replace('OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"password"))},singleLine=true,visualTransformation=PasswordVisualTransformation())',
                'OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text(syncText(lang,"password"))},textStyle=userTextStyle(),singleLine=true,visualTransformation=PasswordVisualTransformation())')
    cloud.write_text(c)

# Version bump. Keep applicationId and DB schema untouched.
g=gradle.read_text()
assert 'versionCode = 68' in g
assert 'versionName = "0.9.35"' in g
g=g.replace('versionCode = 68','versionCode = 69',1)
g=g.replace('versionName = "0.9.35"','versionName = "0.9.36"',1)
gradle.write_text(g)
