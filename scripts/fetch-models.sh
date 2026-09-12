#!/usr/bin/env bash
# Récupère les modèles MediaPipe on-device attendus par AndroidGestureRecognizer.
# Ils sont volontairement hors git (~11 Mo, binaires) — cf. .gitignore.
#
# Usage :  ./scripts/fetch-models.sh        (depuis la racine du repo, Git Bash sous Windows)
#
# La CI ne lance PAS ce script : l'APK de CI valide la compilation, pas l'exécution. Les
# modèles ne sont nécessaires que pour installer et lancer l'app sur un appareil.
set -euo pipefail

RACINE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$RACINE/androidApp/src/main/assets"
BASE="https://storage.googleapis.com/mediapipe-models"

mkdir -p "$ASSETS"

recuperer() {
  local nom="$1" url="$2" taille_min="$3"
  if [ -f "$ASSETS/$nom" ]; then
    echo "= $nom déjà présent — passe"
    return
  fi
  echo "↓ $nom"
  curl -fL --retry 3 -o "$ASSETS/$nom.part" "$url"
  # Un CDN qui renvoie une page d'erreur en 200 produirait un .task de quelques Ko :
  # on refuse tout fichier manifestement trop petit plutôt que de crasher au runtime.
  local taille
  taille=$(wc -c < "$ASSETS/$nom.part")
  if [ "$taille" -lt "$taille_min" ]; then
    rm -f "$ASSETS/$nom.part"
    echo "✗ $nom : $taille octets reçus (< $taille_min attendus) — téléchargement invalide" >&2
    exit 1
  fi
  mv "$ASSETS/$nom.part" "$ASSETS/$nom"
  echo "✓ $nom ($taille octets)"
}

recuperer hand_landmarker.task \
  "$BASE/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" 5000000
recuperer face_landmarker.task \
  "$BASE/face_landmarker/face_landmarker/float16/1/face_landmarker.task" 2000000

echo "Modèles prêts dans $ASSETS"
