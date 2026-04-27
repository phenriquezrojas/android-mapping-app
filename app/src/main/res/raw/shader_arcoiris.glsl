precision highp float;

// Arcoiris - 16 Segment Procedural Font Shader
// Adapted for MappingAndroid engine (v2.1)

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_nl1; // Parameter for line 1 offset
uniform float u_nl2; // Parameter for line 2 offset
uniform float u_nl3; // Parameter for line 3 offset
varying vec2 v_TexCoord;

vec2 uv;

const vec2 ch_size  = vec2(1.0, 2.0) * 0.6;
const vec2 ch_space = ch_size + vec2(1.0, 1.0);
const vec2 ch_start = vec2 (ch_space.x * -1.75, 3.25);
vec2 ch_pos   = vec2 (0.0, 0.0);
float d = 1e6;

float dseg(vec2 p0, vec2 p1) {
    vec2 dir = normalize(p1 - p0);
    vec2 cp = (uv - ch_pos - p0) * mat2(dir.x, dir.y, -dir.y, dir.x);
    return distance(cp, clamp(cp, vec2(0), vec2(distance(p0, p1), 0)));   
}

bool bit(int n, int b) {
    return mod(floor(float(n) / exp2(floor(float(b)))), 2.0) != 0.0;
}

void ddigit(int n) {
    float v = 1e6;	
    if (n == 0) v = min(v, dseg(vec2(-0.405, -1.000), vec2(-0.500, -1.000)));
    if (bit(n,  0)) v = min(v, dseg(vec2( 0.500,  0.063), vec2( 0.500,  0.937)));
    if (bit(n,  1)) v = min(v, dseg(vec2( 0.438,  1.000), vec2( 0.063,  1.000)));
    if (bit(n,  2)) v = min(v, dseg(vec2(-0.063,  1.000), vec2(-0.438,  1.000)));
    if (bit(n,  3)) v = min(v, dseg(vec2(-0.500,  0.937), vec2(-0.500,  0.062)));
    if (bit(n,  4)) v = min(v, dseg(vec2(-0.500, -0.063), vec2(-0.500, -0.938)));
    if (bit(n,  5)) v = min(v, dseg(vec2(-0.438, -1.000), vec2(-0.063, -1.000)));
    if (bit(n,  6)) v = min(v, dseg(vec2( 0.063, -1.000), vec2( 0.438, -1.000)));
    if (bit(n,  7)) v = min(v, dseg(vec2( 0.500, -0.938), vec2( 0.500, -0.063)));
    if (bit(n,  8)) v = min(v, dseg(vec2( 0.063,  0.000), vec2( 0.438, -0.000)));
    if (bit(n,  9)) v = min(v, dseg(vec2( 0.063,  0.063), vec2( 0.438,  0.938)));
    if (bit(n, 10)) v = min(v, dseg(vec2( 0.000,  0.063), vec2( 0.000,  0.937)));
    if (bit(n, 11)) v = min(v, dseg(vec2(-0.063,  0.063), vec2(-0.438,  0.938)));
    if (bit(n, 12)) v = min(v, dseg(vec2(-0.438,  0.000), vec2(-0.063, -0.000)));
    if (bit(n, 13)) v = min(v, dseg(vec2(-0.063, -0.063), vec2(-0.438, -0.938)));
    if (bit(n, 14)) v = min(v, dseg(vec2( 0.000, -0.938), vec2( 0.000, -0.063)));
    if (bit(n, 15)) v = min(v, dseg(vec2( 0.063, -0.063), vec2( 0.438, -0.938)));
    ch_pos.x += ch_space.x;
    d = min(d, v);
}

// Letter definitions
void drawW() { ddigit(0xA099); }
void drawO() { ddigit(0x00FF); }
void drawS() { ddigit(0x8866); }
void drawM() { ddigit(0x0A99); }
void drawE() { ddigit(0x107E); }
void drawT() { ddigit(0x4406); }
void drawR() { ddigit(0x911F); }
void drawL() { ddigit(0x0078); }
void drawA() { ddigit(0x119F); }
void drawG() { ddigit(0x807E); }
void drawSpace() { ch_pos.x += ch_space.x; }

vec3 hsv2rgb_smooth( in vec3 c ) {
    vec3 rgb = clamp( abs(mod(c.x*6.0+vec3(0.0,4.0,2.0),6.0)-3.0)-1.0, 0.0, 1.0 );
    rgb = rgb*rgb*(3.0-2.0*rgb); 
    return c.z * mix( vec3(1.0), rgb, c.y);
}

void main( void ) {
    vec2 aspect = u_resolution.xy / u_resolution.y;
    // Map screen UV to local UV for procedural drawing
    uv = ( v_TexCoord * aspect ) - aspect / 2.0;
    float _d = 1.0-length(uv);
    uv *= 18.0 ;
    uv -= vec2(-7., 1.);

    vec3 ch_color = hsv2rgb_smooth(vec3(u_time*0.4+uv.y*0.1,0.5,1.0));
    vec3 bg_color = vec3(_d*0.4, _d*0.2, _d*0.1);
    
    // [Seamless Sync] Speed fixed to 1.04719 to match 60s cycle (k=10)
    uv.x += 0.5+sin(u_time*1.0471975 + uv.y*0.7)*0.5;
    
    ch_pos = ch_start;

    // Line 1
    drawSpace(); drawSpace(); drawSpace(); drawSpace(); drawSpace(); 
    drawW(); drawO(); drawW(); drawSpace(); 
    ch_pos = ch_start; ch_pos.y -= (u_nl1 != 0.0 ? u_nl1 * 10.0 : 3.0);

    // Line 2
    drawSpace(); drawSpace(); drawSpace(); drawSpace(); 
    drawS(); drawO(); drawM(); drawE(); 
    ch_pos = ch_start; ch_pos.y -= (u_nl2 != 0.0 ? u_nl2 * 10.0 : 6.0);

    // Line 3
    drawSpace(); drawSpace(); drawSpace(); drawSpace(); 
    drawT(); drawR(); drawO(); drawL(); drawL(); drawA(); drawG(); drawE();
    ch_pos = ch_start; ch_pos.y -= (u_nl3 != 0.0 ? u_nl3 * 10.0 : 9.0);
		
    vec3 color = mix(ch_color, bg_color, 1.0- (0.08 / d*2.0));
    gl_FragColor = vec4(color * u_opacity, u_opacity);
}
