// бандл запускается в Web Worker: в нём нет document, а загрузка кусков — через importScripts
config.target = 'webworker';
