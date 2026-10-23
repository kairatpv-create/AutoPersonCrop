#!/usr/bin/env python3
"""Convert the existing approved Android vector to opaque iOS assets; never redesigns the icon."""
from pathlib import Path
import xml.etree.ElementTree as E, json
from PIL import Image, ImageDraw
from fontTools.pens.basePen import BasePen
from fontTools.svgLib.path import parse_path
root=Path(__file__).resolve().parents[2];ns='{http://schemas.android.com/apk/res/android}'
vector=E.parse(root/'mobile/android/app/src/main/res/drawable/ic_app_icon_approved.xml').getroot()
class PolygonPen(BasePen):
    def __init__(self): super().__init__(None);self.contours=[];self.points=[]
    def finish(self):
        if self.points:self.contours.append(self.points);self.points=[]
    def _moveTo(self,p): self.finish();self.points=[p]
    def _lineTo(self,p): self.points.append(p)
    def _curveToOne(self,a,b,c):
        p=self._getCurrentPoint()
        for i in range(1,65):
            t=i/64;u=1-t
            self.points.append(tuple(u**3*p[k]+3*u*u*t*a[k]+3*u*t*t*b[k]+t**3*c[k] for k in [0,1]))
    def _qCurveToOne(self,a,b):
        p=self._getCurrentPoint()
        for i in range(1,65):
            t=i/64;u=1-t
            self.points.append(tuple(u*u*p[k]+2*u*t*a[k]+t*t*b[k] for k in [0,1]))
    def _closePath(self): self.finish()
    def _endPath(self): self.finish()
size=2048;image=Image.new('RGBA',(size,size),'#20BFF2')
for p in vector:
    color=p.attrib[ns+'fillColor'];alpha=255
    if len(color)==9: alpha=int(color[1:3],16);color='#'+color[3:]
    rgba=tuple(int(color[i:i+2],16) for i in [1,3,5])+(alpha,)
    pen=PolygonPen();parse_path(p.attrib[ns+'pathData'],pen);pen.finish()
    layer=Image.new('RGBA',(size,size));draw=ImageDraw.Draw(layer)
    for contour in pen.contours:
        if len(contour)>2:draw.polygon([(x*size/108,y*size/108) for x,y in contour],fill=rgba)
    image.alpha_composite(layer)
assets=root/'mobile/ios/OrganizerPro/Assets.xcassets';assets.mkdir(parents=True,exist_ok=True)
def metadata(path,value): path.mkdir(parents=True,exist_ok=True);(path/'Contents.json').write_text(json.dumps(value,indent=2)+'\n')
metadata(assets,{'info':{'author':'xcode','version':1}})
app=assets/'AppIcon.appiconset';brand=assets/'BrandIcon.imageset'
metadata(app,{'images':[{'filename':'AppIcon.png','idiom':'universal','platform':'ios','size':'1024x1024'}],'info':{'author':'xcode','version':1}})
metadata(brand,{'images':[{'filename':'BrandIcon.png','idiom':'universal'}],'info':{'author':'xcode','version':1}})
im=image.convert('RGB').resize((1024,1024),Image.Resampling.LANCZOS)
im.save(app/'AppIcon.png');im.save(brand/'BrandIcon.png')
metadata(assets/'AccentColor.colorset',{'colors':[{'color':{'color-space':'srgb','components':{'red':'0.039','green':'0.518','blue':'0.847','alpha':'1.000'}},'idiom':'universal'}],'info':{'author':'xcode','version':1}})
metadata(assets/'LaunchBackground.colorset',{'colors':[{'color':{'color-space':'srgb','components':{'red':'0.804','green':'0.937','blue':'1.000','alpha':'1.000'}},'idiom':'universal'}],'info':{'author':'xcode','version':1}})
print('Reused approved Android icon; 1024×1024 RGB, opaque')
