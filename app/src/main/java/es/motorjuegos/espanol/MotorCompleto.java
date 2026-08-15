package es.motorjuegos.espanol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ==========================================
// 1. CLASE PRINCIPAL DEL EDITOR (PANTALLA)
// ==========================================
public class MotorCompleto extends Activity {
    private static final int PICK_IMAGE = 1;
    private static final int PICK_AUDIO = 2;
    private EditText campoCodigo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        Button btnPng = new Button(this);
        btnPng.setText("Agregar PNG desde Galería");
        btnPng.setOnClickListener(v -> abrirGaleria("image/png", PICK_IMAGE));

        Button btnMp3 = new Button(this);
        btnMp3.setText("Agregar MP3 (Sonido)");
        btnMp3.setOnClickListener(v -> abrirGaleria("audio/*", PICK_AUDIO));

        campoCodigo = new EditText(this);
        campoCodigo.setHint("Escribe tus reglas:\nsi \"png_899\" toca a \"png_900\" generar \"png_1\"");
        campoCodigo.setLines(8);

        Button btnProbar = new Button(this);
        btnProbar.setText("▶ Probar Juego");
        btnProbar.setOnClickListener(v -> {
            CompiladorAPK.guardarJuego(this, campoCodigo.getText().toString());
            Toast.makeText(this, "¡Juego guardado y listo!", Toast.LENGTH_SHORT).show();
        });

        layout.addView(btnPng);
        layout.addView(btnMp3);
        layout.addView(campoCodigo);
        layout.addView(btnProbar);

        setContentView(layout);
    }

    private void abrirGaleria(String tipo, int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(tipo);
        startActivityForResult(intent, requestCode);
    }
}

// ==========================================
// 2. MODELO DE OBJETOS DEL JUEGO
// ==========================================
class ObjetoJuego {
    public final String nombre;
    public float x = 100, y = 100, ancho = 120, alto = 120;
    public boolean visible = true;

    public ObjetoJuego(String nombre) {
        this.nombre = nombre;
    }

    public RectF rectangulo() {
        return new RectF(x, y, x + ancho, y + alto);
    }
}

// ==========================================
// 3. PARSER / TRADUCTOR DEL ESPAÑOL
// ==========================================
class ProgramaEspanol {
    public static class EventoChoque {
        public String objetoA, objetoB;
        public ArrayList<String[]> acciones = new ArrayList<>();

        public EventoChoque(String a, String b) {
            this.objetoA = a;
            this.objetoB = b;
        }
    }

    public final LinkedHashMap<String, ObjetoJuego> objetos = new LinkedHashMap<>();
    public final ArrayList<EventoChoque> colisiones = new ArrayList<>();

    public static ProgramaEspanol leer(String codigo) {
        ProgramaEspanol p = new ProgramaEspanol();
        String[] lineas = codigo.replace("\r", "").split("\n");

        for (String lineaRaw : lineas) {
            String linea = lineaRaw.trim();
            if (linea.isEmpty() || linea.startsWith("#")) continue;

            Matcher mGenerar = Pattern.compile("(?i)si\\s+\"([^\"]+)\"\\s+toca a\\s+\"([^\"]+)\"\\s+generar\\s+\"([^\"]+)\"").matcher(linea);
            if (mGenerar.matches()) {
                EventoChoque ev = new EventoChoque(mGenerar.group(1), mGenerar.group(2));
                ev.acciones.add(new String[]{"generar", mGenerar.group(3)});
                p.colisiones.add(ev);
                continue;
            }

            Matcher mSonido = Pattern.compile("(?i)si\\s+\"([^\"]+)\"\\s+toca a\\s+\"([^\"]+)\"\\s+reproducir sonido\\s+\"([^\"]+)\"").matcher(linea);
            if (mSonido.matches()) {
                EventoChoque ev = new EventoChoque(mSonido.group(1), mSonido.group(2));
                ev.acciones.add(new String[]{"sonido", mSonido.group(3)});
                p.colisiones.add(ev);
            }
        }
        return p;
    }
}

// ==========================================
// 4. MOTOR GRÁFICO Y AUDIO
// ==========================================
class JuegoView extends View {
    private final Paint pincel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ProgramaEspanol programa;
    private SoundPool reproductorSonido;

    public JuegoView(Context context, String codigo) {
        super(context);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        reproductorSonido = new SoundPool.Builder().setMaxStreams(5).setAudioAttributes(attrs).build();
        programa = ProgramaEspanol.leer(codigo);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);

        for (ProgramaEspanol.EventoChoque ev : programa.colisiones) {
            ObjetoJuego a = programa.objetos.get(ev.objetoA);
            ObjetoJuego b = programa.objetos.get(ev.objetoB);
            if (a != null && b != null && RectF.intersects(a.rectangulo(), b.rectangulo())) {
                for (String[] accion : ev.acciones) {
                    if (accion[0].equals("generar") && !programa.objetos.containsKey(accion[1])) {
                        ObjetoJuego nuevo = new ObjetoJuego(accion[1]);
                        nuevo.x = 200; nuevo.y = 200;
                        programa.objetos.put(accion[1], nuevo);
                    }
                }
            }
        }

        for (ObjetoJuego obj : programa.objetos.values()) {
            if (obj.visible) {
                pincel.setColor(Color.GREEN);
                canvas.drawRect(obj.rectangulo(), pincel);
            }
        }
        invalidate();
    }
}

// ==========================================
// 5. EXPORTADOR LOCAL DE DATOS
// ==========================================
class CompiladorAPK {
    public static File guardarJuego(Context context, String codigo) {
        File carpeta = new File(context.getExternalFilesDir(null), "JuegosGenerados");
        if (!carpeta.exists()) carpeta.mkdirs();
        File archivo = new File(carpeta, "juego.espanol");
        try (FileOutputStream fos = new FileOutputStream(archivo)) {
            fos.write(codigo.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return archivo;
    }
}
