package com.vocalmonitor.plugin.community;

/**
 * Pure-DSP acoustic feature extractor for the Register Detector.
 *
 * Deliberately has ZERO dependencies on the plugin SDK (no PluginCanvas /
 * PluginHost imports, only java.lang / java.util) so the exact same code
 * runs in two places:
 *
 *   1. inside {@code RegisterDetector} on the phone (live analysis), and
 *   2. inside the offline {@code FeatureExtractor} CLI under
 *      {@code tools/register-trainer/} that builds the training set from
 *      VocalSet wavs.
 *
 * That shared-source guarantee is the whole point: a model trained on
 * features from path (2) only behaves on-device if path (1) computes
 * byte-for-byte the same vector. Re-implementing this in Python/librosa
 * would silently drift (different windowing, LPC, peak interpolation) and
 * wreck accuracy, so we compile this one class into both.
 *
 * Call {@link #analyse(float[], int)} with a frame of exactly {@link #FFT_N}
 * time-ordered samples; on {@code true} the public fields {@link #f0},
 * {@link #h1h2}, {@link #h1a3}, {@link #hrf}, {@link #spr}, {@link #oq}
 * hold the latest measurement. All work buffers are instance fields and
 * reused, so a single instance is cheap to call in a 60 Hz loop (but is
 * NOT thread-safe — one instance per caller thread).
 */
public final class RegisterFeatures {

    // ── FFT / YIN config ──
    public static final int FFT_N      = 2048;       // 21.5 Hz bin @ 44.1 k
    public static final int FFT_HALF   = FFT_N / 2;
    private static final int LAG_MIN    = 32;
    private static final int LAG_MAX    = 1024;
    private static final float YIN_THRESHOLD = 0.15f;

    private final float[] buf       = new float[FFT_N];
    private final float[] yinDiff   = new float[LAG_MAX + 1];
    private final float[] yinCMND   = new float[LAG_MAX + 1];
    private final float[] fftRe     = new float[FFT_N];
    private final float[] fftIm     = new float[FFT_N];
    private final float[] magDb     = new float[FFT_HALF];
    private final float[] hann      = new float[FFT_N];
    {
        for (int i = 0; i < FFT_N; i++) {
            hann[i] = (float)(0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_N - 1))));
        }
    }

    // ── Formant estimation (for the Iseli-Alwan H1*-H2* correction) ──
    private static final int FMT_DEC   = 4;
    private static final int FMT_DECN  = FFT_N / FMT_DEC;   // 512 @ 11.025 kHz
    private static final int FMT_ORDER = 14;
    private final float[]  fmtScratch = new float[FFT_N];
    private final float[]  fmtDec   = new float[FMT_DECN];
    private final float[]  fmtA     = new float[FMT_ORDER + 1];
    private final float[]  fmtR     = new float[FMT_ORDER + 1];
    private final double[] fmtRootRe = new double[FMT_ORDER];
    private final double[] fmtRootIm = new double[FMT_ORDER];
    private final float[]  fmtF     = new float[FMT_ORDER];
    private final float[]  fmtB     = new float[FMT_ORDER];
    private float[] aaCoefs;

    private int sampleRate = 44100;

    // ── Latest measurements (valid only when analyse() returned true) ──
    public float f0   = 0f;
    public float h1h2 = 0f;
    public float h1a3 = 0f;
    public float hrf  = 0f;
    public float spr  = 0f;
    public float oq   = 0.5f;

    /**
     * Analyse one frame of {@link #FFT_N} time-ordered samples. Returns
     * true and fills the public feature fields when the frame is voiced
     * and a confident f0 was found; false (fields left stale) when the
     * frame is silent / unvoiced / out of the 70–1200 Hz range.
     */
    public boolean analyse(float[] frame, int sr) {
        this.sampleRate = sr;
        // ── 1. Energy gate ──
        double energy = 0;
        for (int i = 0; i < FFT_N; i++) {
            float v = frame[i];
            buf[i] = v;
            energy += v * v;
        }
        float rms = (float) Math.sqrt(energy / FFT_N);
        if (rms < 0.003f) return false;

        // ── 2. YIN for f0 ──
        int half = FFT_N / 2;
        int maxLag = Math.min(half, LAG_MAX);
        for (int tau = 1; tau <= maxLag; tau++) {
            float sum = 0f;
            for (int j = 0; j < half; j++) {
                float d = buf[j] - buf[j + tau];
                sum += d * d;
            }
            yinDiff[tau] = sum;
        }
        yinCMND[0] = 1f;
        float running = 0f;
        for (int tau = 1; tau <= maxLag; tau++) {
            running += yinDiff[tau];
            yinCMND[tau] = running > 1e-12f ? yinDiff[tau] * tau / running : 1f;
        }
        int chosen = -1;
        for (int tau = LAG_MIN; tau < maxLag - 1; tau++) {
            if (yinCMND[tau] < YIN_THRESHOLD) {
                while (tau + 1 < maxLag && yinCMND[tau + 1] < yinCMND[tau]) tau++;
                chosen = tau;
                break;
            }
        }
        if (chosen < 0) return false;
        float refined = chosen;
        if (chosen > 0 && chosen < maxLag) {
            float y1 = yinCMND[chosen - 1], y2 = yinCMND[chosen], y3 = yinCMND[chosen + 1];
            float denom = 2f * (2f * y2 - y1 - y3);
            if (Math.abs(denom) > 1e-9f) {
                float adj = (y3 - y1) / denom;
                if (adj > -1f && adj < 1f) refined += adj;
            }
        }
        f0 = sampleRate / refined;
        if (f0 < 70f || f0 > 1200f) return false;

        // ── 3. FFT for spectral features ──
        for (int i = 0; i < FFT_N; i++) {
            fftRe[i] = buf[i] * hann[i];
            fftIm[i] = 0f;
        }
        fft(fftRe, fftIm);
        for (int k = 0; k < FFT_HALF; k++) {
            float mag = (float) Math.sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]);
            magDb[k] = 20f * (float) Math.log10(Math.max(1e-9f, mag));
        }
        float binHz = sampleRate / (float) FFT_N;

        // Harmonic amplitudes H1..H10 (parabolic-interpolated dB).
        float[] hDb = new float[11];
        int nH = 0;
        for (int n = 1; n <= 10; n++) {
            float hHz = n * f0;
            if (hHz > sampleRate * 0.45f) break;
            hDb[n] = peakMagDb(hHz, binHz);
            nH = n;
        }
        if (nH < 2) return false;

        // Formant-correct H1 and H2 (Iseli-Alwan).
        int nf = estimateFormants();
        float h1c = hDb[1], h2c = hDb[2];
        if (nf >= 2) {
            float F1 = fmtF[0], B1 = fmtB[0], F2 = fmtF[1], B2 = fmtB[1];
            if (F1 >= 150f && F1 <= 1300f && F2 >= 700f && F2 <= 3600f) {
                h1c += formantCorr(f0,       F1, B1) + formantCorr(f0,       F2, B2);
                h2c += formantCorr(2f * f0,  F1, B1) + formantCorr(2f * f0,  F2, B2);
            }
        }

        // H1*-H2* (formant-corrected)
        h1h2 = h1c - h2c;

        // HRF (Childers): H1_dB − 10·log10(Σ |Hₙ|² for n=2..nH)
        double higherPower = 0;
        for (int n = 2; n <= nH; n++) {
            double m = Math.pow(10.0, hDb[n] / 20.0);
            higherPower += m * m;
        }
        hrf = hDb[1] - 10f * (float) Math.log10(Math.max(1e-9, higherPower));

        // H1-A3: (corrected) H1 − max(magDb in 2.5..3.5 kHz)
        int kA3Lo = Math.max(1, (int) Math.floor(2500f / binHz));
        int kA3Hi = Math.min(FFT_HALF - 1, (int) Math.ceil(3500f / binHz));
        float a3 = -120f;
        for (int k = kA3Lo; k <= kA3Hi; k++) if (magDb[k] > a3) a3 = magDb[k];
        h1a3 = h1c - a3;

        // SPR (Sundberg): peak dB(2–4 kHz) − peak dB(80–2000 Hz)
        int kLo1 = Math.max(1, (int) Math.floor(80f / binHz));
        int kLo2 = Math.min(FFT_HALF - 1, (int) Math.floor(2000f / binHz));
        int kHi1 = kLo2;
        int kHi2 = Math.min(FFT_HALF - 1, (int) Math.floor(4000f / binHz));
        float pLo = -120f, pHi = -120f;
        for (int k = kLo1; k <= kLo2; k++) if (magDb[k] > pLo) pLo = magDb[k];
        for (int k = kHi1; k <= kHi2; k++) if (magDb[k] > pHi) pHi = magDb[k];
        spr = pHi - pLo;

        // OQ from Henrich's H1-H2 approximation; clamp to [0.2 .. 0.95].
        oq = 0.5f + 0.025f * h1h2;
        if (oq < 0.2f) oq = 0.2f;
        if (oq > 0.95f) oq = 0.95f;

        return true;
    }

    // Magnitude in dB at frequency f, parabolic-interpolated around the
    // nearest FFT bin for sub-bin accuracy.
    private float peakMagDb(float f, float binHz) {
        if (f <= 0f || f >= sampleRate * 0.5f) return -90f;
        float kF = f / binHz;
        int k = Math.round(kF);
        if (k <= 0 || k >= FFT_HALF - 1) {
            if (k < 0 || k >= FFT_HALF) return -90f;
            return magDb[k];
        }
        float y1 = magDb[k - 1], y2 = magDb[k], y3 = magDb[k + 1];
        float denom = (y1 - 2f * y2 + y3);
        // Parabolic refinement is only valid at a concave peak (denom < 0).
        // A requested harmonic that lands in a spectral valley has denom >= 0,
        // where the vertex formula extrapolates upward without bound and
        // produces absurd dB (which then blows H1*-H2* and HRF to ±1e5/-Inf).
        // Fall back to the bin magnitude there, and clamp the offset to a
        // half-bin so even at a peak the result stays physically bounded.
        if (denom > -1e-6f) return y2;
        float p = 0.5f * (y1 - y3) / denom;
        if (p < -0.5f) p = -0.5f; else if (p > 0.5f) p = 0.5f;
        return y2 - 0.25f * (y1 - y3) * p;
    }

    // ── Iseli-Alwan (2007) formant correction ──
    private static float formantCorr(float f, float F, float B) {
        double wB = Math.PI * B;
        double w  = 2.0 * Math.PI * f;
        double wF = 2.0 * Math.PI * F;
        double num1 = wB * wB + (w - wF) * (w - wF);
        double num2 = wB * wB + (w + wF) * (w + wF);
        double den  = wB * wB + wF * wF;
        double c = 10.0 * Math.log10(num1 * num2 / (den * den));
        if (c >  20.0) c =  20.0;
        if (c < -20.0) c = -20.0;
        return (float) c;
    }

    // Estimate formants from the current frame (buf): pre-emphasis →
    // 5 kHz anti-alias → decimate /4 → WLP(14) (autocorr+Levinson
    // fallback) → Durand-Kerner roots. Fills fmtF/fmtB ascending and
    // returns the count.
    private int estimateFormants() {
        if (aaCoefs == null) aaCoefs = lowPassBiquad(5000f, 0.707f, sampleRate);
        float prev = 0f;
        double energy = 0;
        for (int i = 0; i < FFT_N; i++) {
            float v = buf[i] - 0.97f * prev;
            prev = buf[i];
            fmtScratch[i] = v;
            energy += v * v;
        }
        if (Math.sqrt(energy / FFT_N) < 1e-4) return 0;
        float s1a = 0f, s1b = 0f, s1c = 0f, s1d = 0f;
        float s2a = 0f, s2b = 0f, s2c = 0f, s2d = 0f;
        for (int i = 0; i < FFT_N; i++) {
            float x = fmtScratch[i];
            float y1 = aaCoefs[0] * x + aaCoefs[1] * s1a + aaCoefs[2] * s1b
                     - aaCoefs[3] * s1c - aaCoefs[4] * s1d;
            s1b = s1a; s1a = x; s1d = s1c; s1c = y1;
            float y2 = aaCoefs[0] * y1 + aaCoefs[1] * s2a + aaCoefs[2] * s2b
                     - aaCoefs[3] * s2c - aaCoefs[4] * s2d;
            s2b = s2a; s2a = y1; s2d = s2c; s2c = y2;
            fmtScratch[i] = y2;
        }
        for (int i = 0; i < FMT_DECN; i++) {
            float w = (float)(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FMT_DECN - 1)));
            fmtDec[i] = fmtScratch[i * FMT_DEC] * w;
        }
        if (!wlp(fmtDec, FMT_DECN, FMT_ORDER, FMT_ORDER, fmtA)) {
            for (int k = 0; k <= FMT_ORDER; k++) {
                float sum = 0f;
                for (int i = k; i < FMT_DECN; i++) sum += fmtDec[i] * fmtDec[i - k];
                fmtR[k] = sum;
            }
            if (fmtR[0] < 1e-9f) return 0;
            float[] a = new float[FMT_ORDER + 1];
            float[] aPrev = new float[FMT_ORDER + 1];
            float Eerr = fmtR[0];
            a[0] = 1f;
            for (int p = 1; p <= FMT_ORDER; p++) {
                float k = -fmtR[p];
                for (int j = 1; j < p; j++) k -= a[j] * fmtR[p - j];
                k /= Eerr;
                if (k > 0.99f) k = 0.99f; if (k < -0.99f) k = -0.99f;
                System.arraycopy(a, 0, aPrev, 0, p);
                a[p] = k;
                for (int j = 1; j < p; j++) a[j] = aPrev[j] + k * aPrev[p - j];
                Eerr *= 1f - k * k;
                if (Eerr < 1e-9f) Eerr = 1e-9f;
            }
            System.arraycopy(a, 0, fmtA, 0, FMT_ORDER + 1);
        }
        float decSr = sampleRate / (float) FMT_DEC;
        return formantRoots(fmtA, FMT_ORDER, decSr, fmtF, fmtB);
    }

    // Weighted Linear Prediction via the covariance normal equations.
    private boolean wlp(float[] x, int N, int p, int M, float[] aOut) {
        int n0 = Math.max(p, M);
        if (N <= n0 + 2) return false;
        double[][] C = new double[p][p];
        double[] b = new double[p];
        for (int n = n0; n < N; n++) {
            double w = 0;
            for (int k = 1; k <= M; k++) { double v = x[n - k]; w += v * v; }
            if (w <= 0) continue;
            double xn = x[n];
            for (int i = 1; i <= p; i++) {
                double xi = w * x[n - i];
                b[i - 1] -= xi * xn;
                for (int j = i; j <= p; j++) C[i - 1][j - 1] += xi * x[n - j];
            }
        }
        for (int i = 0; i < p; i++)
            for (int j = 0; j < i; j++) C[i][j] = C[j][i];
        double[] sol = new double[p];
        if (!solveLinear(C, b, p, sol)) return false;
        aOut[0] = 1f;
        for (int i = 0; i < p; i++) aOut[i + 1] = (float) sol[i];
        return true;
    }

    // Gaussian elimination with partial pivoting, solves A·out = b.
    private boolean solveLinear(double[][] A, double[] b, int n, double[] out) {
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++)
                if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
            if (Math.abs(M[piv][col]) < 1e-12) return false;
            double[] tmp = M[col]; M[col] = M[piv]; M[piv] = tmp;
            for (int r = 0; r < n; r++) {
                if (r == col) continue;
                double f = M[r][col] / M[col][col];
                for (int c = col; c <= n; c++) M[r][c] -= f * M[col][c];
            }
        }
        for (int i = 0; i < n; i++) out[i] = M[i][n] / M[i][i];
        return true;
    }

    // Durand-Kerner (Weierstrass) — all p complex roots of the monic
    // polynomial. Fills outF/outBw ascending by frequency; returns count.
    private int formantRoots(float[] a, int p, float decSr,
                             float[] outF, float[] outBw) {
        double zr = 1, zi = 0;                 // seed (0.4+0.9i)^i
        for (int i = 0; i < p; i++) {
            fmtRootRe[i] = zr; fmtRootIm[i] = zi;
            double nr = zr * 0.4 - zi * 0.9, ni = zr * 0.9 + zi * 0.4;
            zr = nr; zi = ni;
        }
        for (int it = 0; it < 80; it++) {
            double maxd = 0;
            for (int i = 0; i < p; i++) {
                double pr = 1, pi = 0;         // P(z) via Horner
                for (int k = 1; k <= p; k++) {
                    double nr = pr * fmtRootRe[i] - pi * fmtRootIm[i] + a[k];
                    double ni = pr * fmtRootIm[i] + pi * fmtRootRe[i];
                    pr = nr; pi = ni;
                }
                double dr = 1, di = 0;         // Π_{j≠i}(z_i − z_j)
                for (int j = 0; j < p; j++) {
                    if (j == i) continue;
                    double ar = fmtRootRe[i] - fmtRootRe[j], ai = fmtRootIm[i] - fmtRootIm[j];
                    double nr = dr * ar - di * ai, ni = dr * ai + di * ar;
                    dr = nr; di = ni;
                }
                double den = dr * dr + di * di;
                if (den < 1e-30) continue;
                double qr = (pr * dr + pi * di) / den;
                double qi = (pi * dr - pr * di) / den;
                fmtRootRe[i] -= qr; fmtRootIm[i] -= qi;
                maxd = Math.max(maxd, Math.abs(qr) + Math.abs(qi));
            }
            if (maxd < 1e-10) break;
        }
        int n = 0;
        for (int i = 0; i < p && n < outF.length; i++) {
            if (fmtRootIm[i] < 0) continue;    // one of each conjugate pair
            double rr = fmtRootRe[i], ii = fmtRootIm[i];
            double r = Math.hypot(rr, ii);
            if (r >= 1) { rr /= r * r; ii /= r * r; r = 1 / r; }   // reflect inside
            if (r <= 0 || r >= 1) continue;
            double th = Math.atan2(ii, rr);
            if (th < 0) th += 2 * Math.PI;
            float f  = (float) (th * decSr / (2 * Math.PI));
            float bw = (float) (-Math.log(r) * decSr / Math.PI);
            if (f < 90f || f > 5500f || bw > 700f) continue;
            outF[n] = f; outBw[n] = bw; n++;
        }
        for (int i = 1; i < n; i++) {          // insertion sort ascending
            float kf = outF[i], kbw = outBw[i];
            int j = i - 1;
            while (j >= 0 && outF[j] > kf) {
                outF[j + 1] = outF[j]; outBw[j + 1] = outBw[j]; j--;
            }
            outF[j + 1] = kf; outBw[j + 1] = kbw;
        }
        return n;
    }

    // RBJ-cookbook low-pass biquad, normalised to a0=1 form.
    private static float[] lowPassBiquad(float fc, float q, int sr) {
        double w = 2.0 * Math.PI * fc / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double a0 = 1 + alpha;
        return new float[] {
            (float)((1 - cs) * 0.5 / a0),
            (float)((1 - cs)        / a0),
            (float)((1 - cs) * 0.5 / a0),
            (float)(-2 * cs        / a0),
            (float)((1 - alpha)    / a0),
        };
    }

    // In-place radix-2 Cooley-Tukey FFT.
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) { j ^= bit; bit >>= 1; }
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float wpr = 1f, wpi = 0f;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    int a = i + k, b = a + half;
                    float tr = wpr * re[b] - wpi * im[b];
                    float ti = wpr * im[b] + wpi * re[b];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float nwpr = wpr * wRe - wpi * wIm;
                    wpi = wpr * wIm + wpi * wRe;
                    wpr = nwpr;
                }
            }
        }
    }
}
