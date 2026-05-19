const canvas = document.getElementById('canvas');

if (state.movements.includes('micro')) {
    x += Math.sin(time * 0.002 + index) * 4;
    y += Math.cos(time * 0.002 + index) * 4;
}

if (state.movements.includes('elastic')) {
    const push = Math.sin(time * 0.005 + index) * state.energy * 0.3;
    x += push;
    y += push;
}

const radius = 26 + state.beat * 8;

const glow = identity.glow * 20;

ctx.beginPath();
ctx.arc(x, y, radius, 0, Math.PI * 2);

const gradient = ctx.createRadialGradient(x, y, 0, x, y, radius + glow);

if (state.scene === 'pulse_core') {
    gradient.addColorStop(0, 'rgba(255,255,255,1)');
    gradient.addColorStop(1, 'rgba(0,120,255,0)');
}

if (state.scene === 'neon_grid') {
    gradient.addColorStop(0, 'rgba(255,0,255,1)');
    gradient.addColorStop(1, 'rgba(255,0,255,0)');
}

if (state.scene === 'orbital') {
    gradient.addColorStop(0, 'rgba(0,255,200,1)');
    gradient.addColorStop(1, 'rgba(0,255,200,0)');
}

ctx.fillStyle = gradient;
ctx.fill();
  });
}

function loop(time) {
    updateSimulation();
    drawNodes(time);
    requestAnimationFrame(loop);
}

loadSimulation().then(() => {
    requestAnimationFrame(loop);
});

setInterval(() => {
    state.beat = Math.random();
}, 120);

document.getElementById('play-btn').onclick = () => {
    state.playing = true;
};

document.getElementById('pause-btn').onclick = () => {
    state.playing = false;
};