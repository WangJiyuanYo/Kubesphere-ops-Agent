'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const root = path.resolve(__dirname, '../../src/main/resources/static');
const settle = () => new Promise(resolve => setImmediate(resolve));

async function view() {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'index.html'), 'utf8'), { runScripts:'outside-only',url:'http://127.0.0.1/' });
  const win=dom.window, requests=[];
  win.TextEncoder=TextEncoder;win.setInterval=()=>0;
  win.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  win.HTMLDialogElement.prototype.close=function(){this.open=false;};
  const stage={siteId:'10',provisioningId:'100',deploymentVersion:'cycle-1',workspaceId:'ws',phase:'PROJECT_PREPARE',state:'FAILED',namespace:'team-a',summary:'<script>unsafe</script> 平台失败',sequence:4,dependencies:['ACCEPTED']};
  const watch={id:'watch-1',revision:9,stage,phaseStartedAt:'2026-11-13T00:00:00Z',lastMeaningfulProgressAt:'2026-11-13T00:00:00Z',paused:false,observationWarning:'HTTP 403，观察降级'};
  const scope={workspaceId:'ws',siteId:'10',projectId:null,connectionId:'connection-1',namespace:'team-a',level:'APPROVAL',enabled:true,stallSeconds:600,totalSeconds:3600,maxDiagnoses:2,cooldownSeconds:60};
  const policy={id:'policy-1',revision:7,scope};
  win.fetch=async (url,options)=>{
    requests.push({url,options});let body;
    if(url==='/api/console/bootstrap')body={username:'operator',mode:'DEMO',connections:[{id:'connection-1',name:'测试连接',namespace:'team-a'}],runs:[],sessions:[],llm:{enabled:false,provider:'OPENAI_COMPATIBLE',baseUrl:'https://model.test',model:'fixture',temperature:0.1,maxTokens:512}};
    else if(url.startsWith('/api/console/provisioning-watches?'))body={source:{enabled:true,warning:'阶段同步部分完成'},items:[{watch,policy,anomaly:'OBSERVATION_DEGRADED'}]};
    else if(url==='/api/console/provisioning-watches/watch-1')body={item:{watch,policy},timeline:[stage],triggers:[{state:'MANUAL',detail:'没有 CR，不执行恢复'}],recovery:null};
    else if(url.endsWith('/pause')){watch.paused=JSON.parse(options.body).paused;watch.revision++;body=watch;}
    else if(url==='/api/console/autonomy-policies')body=[policy];
    else if(url.startsWith('/api/console/autonomy-policies/'))body=policy;
    else if(url==='/api/console/learning-outcomes')body={note:'按真实验收区分结果',counts:[{outcome:'NATURAL_CONVERGENCE',samples:2}],recent:[]};
    else throw new Error('Unexpected URL '+url);
    return {status:200,ok:true,text:async()=>JSON.stringify(body)};
  };
  win.eval(fs.readFileSync(path.join(root,'app.js'),'utf8'));
  const doc=win.document;doc.querySelector('#login-form input[name="password"]').value='fixture-only';doc.querySelector('#login-form button').click();await settle();
  doc.querySelector('[data-page="watches"]').click();await settle();
  return {dom,doc,requests};
}

test('site timeline shows observation gaps and escaped evidence; pause binds the current revision',async()=>{
  const {dom,doc,requests}=await view();
  try {
    assert.equal(doc.getElementById('page-watches').hidden,false);
    assert.match(doc.getElementById('watch-list').textContent,/HTTP 403/);
    assert.match(doc.getElementById('learning-outcomes').textContent,/自然收敛：2/);
    doc.querySelector('#watch-list button').click();await settle();
    assert.match(doc.getElementById('watch-detail').textContent,/<script>unsafe<\/script>/);
    assert.equal(doc.querySelector('#watch-detail script'),null);
    doc.querySelector('#watch-detail button').click();await settle();
    const request=requests.find(r=>r.url.endsWith('/pause'));
    assert.deepEqual(JSON.parse(request.options.body),{revision:9,paused:true});
    assert.match(doc.getElementById('watch-list').textContent,/已暂停/);
  }finally{dom.window.close();}
});

test('policy editing freezes scope and saves the displayed policy revision; new policies start disabled',async()=>{
  const {dom,doc,requests}=await view();
  try {
    doc.querySelector('#policy-list button').click();
    assert.equal(doc.getElementById('policy-workspace').readOnly,true);
    assert.equal(doc.getElementById('policy-connection').disabled,true);
    doc.getElementById('policy-level').value='DIAGNOSE';
    doc.querySelector('#policy-form button[type="submit"]').click();await settle();
    const request=requests.find(r=>r.options.method==='PUT');assert.equal(JSON.parse(request.options.body).revision,7);assert.equal(JSON.parse(request.options.body).level,'DIAGNOSE');
    doc.getElementById('new-policy').click();assert.equal(doc.getElementById('policy-enabled').checked,false);assert.equal(doc.getElementById('policy-level').value,'OBSERVE');
  }finally{dom.window.close();}
});
