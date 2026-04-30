const express = require('express');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = 3000;

app.use(express.json());
app.use(express.static('public'));

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.get('/:page', (req, res) => {
  const page = req.params.page;
  if (page.includes('.')) {
    const filePath = path.join(__dirname, 'public', page);
    if (fs.existsSync(filePath)) {
      res.sendFile(filePath);
    } else {
      res.status(404).send('Not found');
    }
  } else {
    const filePath = path.join(__dirname, 'public', page + '.html');
    if (fs.existsSync(filePath)) {
      res.sendFile(filePath);
    } else {
      res.status(404).send('Not found');
    }
  }
});

app.get('/api/delay/:seconds', (req, res) => {
  const seconds = parseInt(req.params.seconds) || 1;
  setTimeout(() => {
    res.json({ message: 'Delayed response', delay: seconds });
  }, seconds * 1000);
});

app.get('/api/cookies', (req, res) => {
  res.json(req.cookies || {});
});

app.post('/api/cookies', (req, res) => {
  const cookies = req.body;
  Object.entries(cookies).forEach(([name, value]) => {
    res.cookie(name, value);
  });
  res.json({ success: true, cookies });
});

app.get('/api/slow-response', (req, res) => {
  setTimeout(() => {
    res.json({ message: 'Slow response', data: 'test data' });
  }, 10000);
});

app.get('/api/download', (req, res) => {
  res.setHeader('Content-Disposition', 'attachment; filename=test.txt');
  res.setHeader('Content-Type', 'text/plain');
  res.send('Test file content');
});

app.get('/api/slow-download', (req, res) => {
  res.setHeader('Content-Disposition', 'attachment; filename=slow.txt');
  res.setHeader('Content-Type', 'text/plain');
  res.write('Line 1\n');
  setTimeout(() => res.write('Line 2\n'), 1000);
  setTimeout(() => res.write('Line 3\n'), 2000);
  setTimeout(() => res.end(), 3000);
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Test app listening on port ${PORT}`);
});