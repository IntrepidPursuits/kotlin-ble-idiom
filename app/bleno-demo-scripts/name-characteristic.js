var util = require('util');
var os = require('os');
var exec = require('child_process').exec;

var bleno = require('../..');

var Descriptor = bleno.Descriptor;
var Characteristic = bleno.Characteristic;

var svcName = "BatSvc1"

var NameCharacteristic = function() {
  NameCharacteristic.super_.call(this, {
    uuid: '3A00',
    properties: ['read', 'write'],
    descriptors: [
      new Descriptor({
        uuid: '2901',
        value: 'Battery Name'
      })
    ]
  });
};

util.inherits(NameCharacteristic, Characteristic);

NameCharacteristic.prototype.onReadRequest = function(offset, callback) {
  // return hardcoded value
  console.log("Read Name: "+svcName);
  callback(this.RESULT_SUCCESS, new Buffer(svcName));
};

NameCharacteristic.prototype.onWriteRequest = function(data, offset, withoutResponse, callback) {
  svcName = "" + data
  callback(this.RESULT_SUCCESS, data);
  console.log("Write Name: "+svcName);
};

module.exports = NameCharacteristic;
