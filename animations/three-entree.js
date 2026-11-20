// Les seules pièces de Three.js dont la page d'accueil a besoin. L'assembleur écarte le reste :
// le fichier servi pèse ainsi une fraction de la bibliothèque complète.
export {
    AmbientLight,
    BoxGeometry,
    Color,
    DirectionalLight,
    Group,
    InstancedMesh,
    MeshStandardMaterial,
    Object3D,
    PerspectiveCamera,
    Scene,
    SRGBColorSpace,
    WebGLRenderer,
} from 'three';
