
const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');

function resize(){
canvas.width = canvas.clientWidth;
canvas.height = canvas.clientHeight;
}
window.addEventListener('resize', resize);
resize();

const state = {
scene:'pulse_core',
layout:'diamond',
identity:'cyber_temple',
movements:['micro'],
beat:0,
energy:0,
playing:true,
packets:[]
};

const identities=[
{id:'cyber_temple',name:'Cyber Temple',color:'#00ccff'},
{id:'rave_machine',name:'Rave Machine',color:'#ff00ff'},
{id:'liquid_dream',name:'Liquid Dream',color:'#00ffaa'}
];

const scenes=[
{id:'pulse_core',name:'Pulse Core'},
{id:'neon_grid',name:'Neon Grid'},
{id:'orbital',name:'Orbital'}
];

const layouts={
diamond:[[-1,-3],[0,-3],[-2,-2],[-1,-2],[0,-2],[1,-2],[-3,-1],[-2,-1],[-1,-1],[0,-1],[1,-1],[2,-1],[-1,0],[0,0],[-1,1],[0,1]],
crown:[[-2,-2],[-1,-2],[1,-2],[2,-2],[-3,-1],[-2,-1],[2,-1],[3,-1],[-3,1],[-2,1],[2,1],[3,1],[-2,2],[-1,2],[1,2],[2,2]],
arrow:[[0,-3],[-1,-2],[0,-2],[1,-2],[-3,-1],[-2,-1],[-1,-1],[0,-1],[1,-1],[2,-1],[3,-1],[-1,0],[0,0],[1,0],[0,1],[0,2]]
};

const movements=[
{id:'micro',name:'Micro Motion'},
{id:'elastic',name:'Elastic'},
{id:'propagation',name:'Propagation'}
];

function createCards(containerId, items, type){
const container=document.getElementById(containerId);

items.forEach((item,index)=>{
const card=document.createElement('div');
card.className='card';

card.innerHTML=`
<div class="preview"></div>
<div class="card-title">${item.name}</div>
`;

if(index===0){card.classList.add('active');}

card.onclick=()=>{
if(type==='scene') state.scene=item.id;
if(type==='identity') state.identity=item.id;

document.querySelectorAll(`#${containerId} .card`).forEach(c=>c.classList.remove('active'));
card.classList.add('active');
};

container.appendChild(card);
});
}

function createLayoutCards(){
const container=document.getElementById('layout-list');

Object.keys(layouts).forEach((key,index)=>{
const card=document.createElement('div');
card.className='card';

card.innerHTML=`
<div class="preview"></div>
<div class="card-title">${key}</div>
`;

if(index===0){card.classList.add('active');}

card.onclick=()=>{
state.layout=key;

document.querySelectorAll('#layout-list .card').forEach(c=>c.classList.remove('active'));
card.classList.add('active');
};

container.appendChild(card);
});
}

function createMovementCards(){
const container=document.getElementById('movement-list');

movements.forEach((movement,index)=>{
const card=document.createElement('div');
card.className='card';

card.innerHTML=`
<div class="preview"></div>
<div class="card-title">${movement.name}</div>
`;

if(index===0){card.classList.add('active');}

card.onclick=()=>{
const exists=state.movements.includes(movement.id);

if(exists){
state.movements=state.movements.filter(m=>m!==movement.id);
card.classList.remove('active');
}else{
state.movements.push(movement.id);
card.classList.add('active');
}
};

container.appendChild(card);
});
}

createCards('identity-list', identities, 'identity');
createCards('scene-list', scenes, 'scene');
createLayoutCards();
createMovementCards();

async function loadSimulation(){
const response=await fetch('../data/numark_simulator_30s.json');
state.packets=await response.json();
}

let packetIndex=0;

function updateSimulation(){
if(!state.playing || !state.packets.length) return;

const packet=state.packets[packetIndex];

state.beat=packet.beat;
state.energy=packet.energy;

document.getElementById('bpm').innerText=packet.bpm;
document.getElementById('energy').innerText=packet.energy.toFixed(2);
document.getElementById('scene-name').innerText=state.scene;
document.getElementById('layout-name').innerText=state.layout;

packetIndex=(packetIndex+1)%state.packets.length;
}

function drawNodes(time){
ctx.clearRect(0,0,canvas.width,canvas.height);

const nodes=layouts[state.layout];
const centerX=canvas.width/2;
const centerY=canvas.height/2;

const identity=identities.find(i=>i.id===state.identity);

nodes.forEach((node,index)=>{

let x=centerX + node[0]*70;
let y=centerY + node[1]*70;

if(state.movements.includes('micro')){
x += Math.sin(time*0.002+index)*4;
y += Math.cos(time*0.002+index)*4;
}

if(state.movements.includes('elastic')){
const push=Math.sin(time*0.005+index)*state.energy*8;
x += push;
y += push;
}

const radius=22 + state.beat*10;

const gradient=ctx.createRadialGradient(x,y,0,x,y,radius+35);

if(state.scene==='pulse_core'){
gradient.addColorStop(0,'rgba(255,255,255,1)');
gradient.addColorStop(0.4,identity.color);
gradient.addColorStop(1,'rgba(0,0,0,0)');
}

if(state.scene==='neon_grid'){
gradient.addColorStop(0,'#ff00ff');
gradient.addColorStop(0.5,'#5500ff');
gradient.addColorStop(1,'rgba(0,0,0,0)');
}

if(state.scene==='orbital'){
gradient.addColorStop(0,'#00ffaa');
gradient.addColorStop(0.5,'#00ccff');
gradient.addColorStop(1,'rgba(0,0,0,0)');
}

ctx.beginPath();
ctx.arc(x,y,radius,0,Math.PI*2);
ctx.fillStyle=gradient;
ctx.fill();
});
}

function loop(time){
updateSimulation();
drawNodes(time);
requestAnimationFrame(loop);
}

document.getElementById('play-btn').onclick=()=>{
state.playing=true;
};

document.getElementById('pause-btn').onclick=()=>{
state.playing=false;
};

loadSimulation().then(()=>{
requestAnimationFrame(loop);
});
