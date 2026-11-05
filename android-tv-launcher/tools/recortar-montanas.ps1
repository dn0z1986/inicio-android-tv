# Recorta las montañas del fondo para ponerlas delante de la hora (efecto profundidad).
# Por cada columna busca, de arriba abajo, el primer punto con textura oscura de roca o bosque
# (el cielo y las nubes no tienen píxeles oscuros cerca). Por encima de esa línea todo es cielo.
# Salida: assets\frente.png (la franja superior del fondo, con el cielo transparente)
#         tools\debug-cresta.jpg (el fondo con la línea detectada en rojo, para revisar)
param(
  [string]$Fondo = "$PSScriptRoot\..\assets\fondo.jpg",
  [int]$Alto = 560,          # solo hace falta la franja donde está la hora
  [int]$Radio = 5,           # tamaño de la ventana de textura
  [int]$Oscuro = 135         # brillo mínimo por debajo del cual es roca/bosque
)
Add-Type -AssemblyName System.Drawing
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class Cresta {
    public static int[] Detectar(Bitmap bmp, int alto, int radio, int oscuro) {
        int w = bmp.Width, h = Math.Min(alto, bmp.Height);
        var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
        byte[] px = new byte[data.Stride * h];
        Marshal.Copy(data.Scan0, px, 0, px.Length);
        bmp.UnlockBits(data);

        // brillo de cada píxel
        int[,] lum = new int[h, w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * data.Stride + x * 3;
                int b = px[i], g = px[i + 1], r = px[i + 2];
                lum[y, x] = (b * 114 + g * 587 + r * 299) / 1000;
                // el cielo azul también es oscuro, pero claramente azul: no cuenta como montaña
                if (b > g + 15 && b > r + 40) lum[y, x] = 255;
            }

        int[] ridge = new int[w];
        for (int x = 0; x < w; x++) {
            ridge[x] = h;
            for (int y = 0; y < h; y++) {
                int dark = 0;
                for (int dy = 0; dy <= radio && y + dy < h; dy++)
                    for (int dx = -radio; dx <= radio; dx++) {
                        int xx = x + dx; if (xx < 0 || xx >= w) continue;
                        if (lum[y + dy, xx] < oscuro) dark++;
                    }
                if (dark >= 3) { ridge[x] = y; break; }
            }
        }
        // mediana horizontal para quitar picos sueltos
        int[] smooth = new int[w];
        int k = 4;
        for (int x = 0; x < w; x++) {
            var win = new System.Collections.Generic.List<int>();
            for (int d = -k; d <= k; d++) { int xx = x + d; if (xx >= 0 && xx < w) win.Add(ridge[xx]); }
            win.Sort(); smooth[x] = win[win.Count / 2];
        }
        return smooth;
    }

    public static Bitmap Frente(Bitmap src, int[] ridge, int alto) {
        int w = src.Width, h = Math.Min(alto, src.Height);
        var outp = new Bitmap(w, h, PixelFormat.Format32bppArgb);
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++) {
                int a = y >= ridge[x] + 2 ? 255 : (y >= ridge[x] - 1 ? 128 : 0);   // borde suavizado
                if (a == 0) continue;
                Color c = src.GetPixel(x, y);
                outp.SetPixel(x, y, Color.FromArgb(a, c));
            }
        return outp;
    }
}
'@

$src = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Fondo))
$ridge = [Cresta]::Detectar($src, $Alto, $Radio, $Oscuro)
$frente = [Cresta]::Frente($src, $ridge, $Alto)
$frente.Save("$PSScriptRoot\..\assets\frente.png", [System.Drawing.Imaging.ImageFormat]::Png)

$dbg = New-Object System.Drawing.Bitmap $src
$g = [System.Drawing.Graphics]::FromImage($dbg)
$pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::Red), 3
for ($x = 1; $x -lt $ridge.Length; $x++) { $g.DrawLine($pen, $x - 1, $ridge[$x - 1], $x, $ridge[$x]) }
$g.Dispose(); $dbg.Save("$PSScriptRoot\debug-cresta.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg)
$src.Dispose(); $frente.Dispose(); $dbg.Dispose()

# Altura de la cresta en la zona de la hora (x 560-1360), para colocar el reloj
"cresta x=560..1360 cada 80px: " + ((560..1360 | Where-Object { $_ % 80 -eq 0 } | ForEach-Object { "$($_):$($ridge[$_])" }) -join '  ')
