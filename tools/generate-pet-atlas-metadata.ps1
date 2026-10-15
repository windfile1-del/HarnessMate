param(
    [Parameter(Mandatory=$true)][string]$ImagePath,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [int]$Rows = 8,
    [int]$Columns = 4,
    [ValidateSet('neutral','blue')][string]$HairPalette = 'neutral',
    [int[]]$RowCuts = @()
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if (-not ('PetSilhouetteReader' -as [type])) {
    Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Collections.Generic;
public class PetSilhouette { public int Left,Top,Right,Bottom,Count; }
public static class PetSilhouetteReader {
 public static List<PetSilhouette> Read(Bitmap b) {
  return Read(b,new int[0]);
 }
 public static List<PetSilhouette> Read(Bitmap b,int[] cuts) {
  var boundaries=new HashSet<int>(cuts);
  int w=b.Width,h=b.Height;var solid=new bool[w*h];var seen=new bool[w*h];var queue=new int[w*h];
  for(int y=0;y<h;y++)for(int x=0;x<w;x++)solid[y*w+x]=b.GetPixel(x,y).A>=128;
  var result=new List<PetSilhouette>();
  for(int start=0;start<solid.Length;start++) {
   if(!solid[start]||seen[start])continue;
   int head=0,tail=0;queue[tail++]=start;seen[start]=true;
   var c=new PetSilhouette {Left=w,Top=h};
   while(head<tail){int p=queue[head++],x=p%w,y=p/w;c.Count++;
    c.Left=Math.Min(c.Left,x);c.Top=Math.Min(c.Top,y);c.Right=Math.Max(c.Right,x+1);c.Bottom=Math.Max(c.Bottom,y+1);
    if(x>0)Push(p-1,solid,seen,queue,ref tail);if(x<w-1)Push(p+1,solid,seen,queue,ref tail);
    if(y>0&&!boundaries.Contains(y))Push(p-w,solid,seen,queue,ref tail);if(y<h-1&&!boundaries.Contains(y+1))Push(p+w,solid,seen,queue,ref tail);
   }
   if(c.Count>=1500)result.Add(c);
  }
  return result;
 }
 private static void Push(int p,bool[] solid,bool[] seen,int[] queue,ref int tail){if(solid[p]&&!seen[p]){seen[p]=true;queue[tail++]=p;}}
}
'@
}
$atlas = [System.Drawing.Bitmap]::FromFile((Resolve-Path -LiteralPath $ImagePath).Path)
try {
    if ($RowCuts.Count -gt 0 -and ($RowCuts.Count -ne $Rows-1 -or @($RowCuts | Where-Object { $_ -le 0 -or $_ -ge $atlas.Height }).Count -gt 0)) {
        throw 'RowCuts must specify one measured seam between each pair of rows.'
    }
    $components = @([PetSilhouetteReader]::Read($atlas,$RowCuts) | Sort-Object Top,Left)
    if ($components.Count -ne $Rows*$Columns) {
        throw "Atlas has $($components.Count) principal silhouettes; expected $($Rows*$Columns). Inspect image; no metadata written."
    }
    $frames = @()
    for ($row=0; $row -lt $Rows; $row++) {
        $entries = @($components[($row*$Columns)..($row*$Columns+$Columns-1)] | Sort-Object Left)
        for ($column=0; $column -lt $Columns; $column++) {
            $entry=$entries[$column]; $centres=@()
            $headTop=$entry.Top+[int](($entry.Bottom-$entry.Top)*0.20)
            $headBottom=$entry.Top+[int](($entry.Bottom-$entry.Top)*0.40)
            for($scanY=$headTop; $scanY -lt $headBottom; $scanY++) {
                $hairLeft=$entry.Right; $hairRight=$entry.Left
                for($scanX=$entry.Left; $scanX -lt $entry.Right; $scanX++) {
                    $pixel=$atlas.GetPixel($scanX,$scanY)
                    $hair = if ($HairPalette -eq 'blue') {
                        $pixel.B -gt 85 -and $pixel.B -gt $pixel.R*1.20 -and $pixel.G -gt 40
                    } else {
                        $pixel.R -gt 130 -and $pixel.G -gt 120 -and $pixel.B -gt 115 -and
                        [Math]::Abs([int]$pixel.R-[int]$pixel.G) -lt 35 -and [Math]::Abs([int]$pixel.G-[int]$pixel.B) -lt 35
                    }
                    if($pixel.A -ge 200 -and $hair) {
                        $hairLeft=[Math]::Min($hairLeft,$scanX);$hairRight=[Math]::Max($hairRight,$scanX+1)
                    }
                }
                if($hairRight-$hairLeft -gt 35) {$centres+=($hairLeft+$hairRight)/2.0}
            }
            $centres=@($centres | Sort-Object)
            $anchor=if($centres.Count -gt 0){$centres[[int][Math]::Floor($centres.Count/2)]}else{($entry.Left+$entry.Right)/2.0}
            $rowTop=if($row -eq 0 -or $RowCuts.Count -eq 0){0}else{$RowCuts[$row-1]}
            $rowBottom=if($row -eq $Rows-1 -or $RowCuts.Count -eq 0){$atlas.Height}else{$RowCuts[$row]}
            $frames += [ordered]@{row=$row;column=$column;left=[Math]::Max(0,$entry.Left-1);top=[Math]::Max($rowTop,$entry.Top-1);
                right=[Math]::Min($atlas.Width,$entry.Right+1);bottom=[Math]::Min($rowBottom,$entry.Bottom+1);
                anchorX=$anchor;baselineY=$entry.Bottom}
        }
    }
    $metadata=[ordered]@{version=1;width=$atlas.Width;height=$atlas.Height;
        alignment='actual connected silhouettes; head-centre-and-feet; shared scale across frames';hairPalette=$HairPalette;rowCuts=$RowCuts;frames=$frames}
    $target=$ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
    [System.IO.File]::WriteAllText($target,($metadata | ConvertTo-Json -Depth 6),[System.Text.UTF8Encoding]::new($false))
    Write-Output "Wrote $($frames.Count) frame registrations to $target; PNG unchanged."
} finally { $atlas.Dispose() }
