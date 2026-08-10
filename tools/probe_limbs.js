// Replicate engine.js geometry math (no GL) to compute real world-space sizes.
// Unit capsule: radius 1, mid-segment len=1.0 -> total Y = len + 2*radius = 3.0, radius=1.
// putIn applies scale (sx,sy,sz): world radius = sx, world total-Y = 3.0*sy.
function capsuleLen(sy) { return 3.0 * sy; }

const B = { shoulderY: 0.10, upperArm: 0.29, foreArm: 0.26, thigh: 0.46, shin: 0.43 };
const chestY = 0.96 + 0.076 + 0.174;
const shoulderY = chestY + B.shoulderY;
const upperNodeY = shoulderY - B.upperArm * 0.5;

function report(name, sy, sx, centerY) {
  const len = capsuleLen(sy);
  const top = (centerY + len / 2).toFixed(3);
  const bot = (centerY - len / 2).toFixed(3);
  console.log(name + ' sy=' + sy + ' -> length=' + len.toFixed(3) + ' radius=' + sx.toFixed(3) + ' spansY=[' + bot + ', ' + top + ']');
}

console.log('Character total height ~1.69. Feet bottom ~ -0.05, hair top ~ 1.64\n');
report('upperArm(sy=1)  ', 1.0, 0.034, upperNodeY + 0.044);
report('upperArm(fixed) ', B.upperArm * 0.30, 0.034, upperNodeY + 0.044);
report('foreArm        ', B.foreArm * 0.32, 0.026, (shoulderY - B.upperArm) - B.foreArm * 0.5);
report('thigh          ', B.thigh * 0.30, 0.049, (0.96 - 0.052) - B.thigh * 0.5);
report('shin           ', B.shin * 0.30, 0.041, (0.96 - 0.052 - B.thigh) - B.shin * 0.5);
