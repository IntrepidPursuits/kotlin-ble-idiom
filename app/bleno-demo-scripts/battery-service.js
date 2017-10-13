var util = require('util');

var bleno = require('../..');

var BlenoPrimaryService = bleno.PrimaryService;

var BatteryLevelCharacteristic = require('./battery-level-characteristic');
var NameCharacteristic = require('./name-characteristic');

function BatteryService() {
  BatteryService.super_.call(this, {
      uuid: '790a4cfa-4058-4922-93f6-d9a5e168cc60',
      characteristics: [
          new BatteryLevelCharacteristic(), new NameCharacteristic()
      ]
  });
}

util.inherits(BatteryService, BlenoPrimaryService);

module.exports = BatteryService;
