package pa2g02;

import java.awt.Color;
import java.util.HashMap;
import java.util.Random;

import robocode.BulletHitEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.Robot;
import robocode.ScannedRobotEvent;
import robocode.util.Utils;

public class PredictionSniper extends Robot {
	//敵情報を格納するクラス
	class Enemy {
		String name;
		boolean living = true; //敵が生きているか
		double gravPower = -1000; //この敵が持つ斥力。反重力運動に使う
		long time = 0; //情報を取得したターン
		double energy = java.lang.Double.NaN;
		double x = java.lang.Double.NaN;
		double y = java.lang.Double.NaN;
		double vel = java.lang.Double.NaN; //速度(robocodeではバック移動をマイナス値で表現しているが、ここではプラスに変えて敵角度を180度反転することで表現する)
		double acc = java.lang.Double.NaN; //加速度
		double deg = java.lang.Double.NaN; //敵ロボットの角度(停止時は頭の向き。敵が移動していればどの方向に移動しているかを直交座標系で)
		double distance = java.lang.Double.NaN;
	}

	double fieldWidth;
	double fieldHeight;
	double bulletPower; //弾のpower
	long previousFullScanTurn = 0; //前回のフルスキャンしたターン数
	boolean meleeMode = true; //今が乱戦状態ならtrue
	boolean lockOnMode = false; //敵をロックオンしているならtrue
	Enemy lockOnEnemy = null;
	HashMap<String, Enemy> enemys = new HashMap<String, Enemy>(); //Enemyクラス群を管理するMap
	double mediumDistanceMoveDegree = 90; //敵lockon時の中距離移動に使う値

	@Override
	public void run() {
		initAny();
		while (true) {
			this.turnRadarLeft(360);
			moveLogic();
			MeleeChecker();
			enemyLivingChecker();
			if (lockOnMode) {
				lockOnRun();
			}
		}
	}

	@Override
	public void onBulletHit(BulletHitEvent event) {
		//敵情報を記録
		enemys.putIfAbsent(event.getName(), new Enemy());
		Enemy en = enemys.get(event.getName());
		en.name = event.getName();
		en.energy = event.getEnergy();
		//敵HPが0ならliving=False
		if (en.energy <= 0) {
			en.living = false;
			//lockonしている敵であればlockon解除
			if (lockOnMode) {
				if (en.name.equals(lockOnEnemy.name)) {
					lockOnMode = false;
				}
			}
		}
	}

	@Override
	public void onHitRobot(HitRobotEvent event) {
		//敵情報を記録
		enemys.putIfAbsent(event.getName(), new Enemy());
		Enemy en = enemys.get(event.getName());
		en.vel = 0;
		en.acc = 0;
		en.x = this.getX()
				+ 36 * Math.cos(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
		en.y = this.getY()
				+ 36 * Math.sin(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
		en.energy = event.getEnergy();
		en.distance = 36;
		en.time = this.getTime();

		//ランダムで右か左に避ける。壁に当たってしまいそうなときはランダム結果の逆を選択
		Random random = new Random();
		double randomdegree = 100 - 200 * random.nextInt(2); //100or-100
		double turnDegree = event.getBearing() + randomdegree;
		double cartesianDegree = fixDegreeToCartesian(turnDegree + this.getHeading()); //直交座標系での移動角
		double predictionX = this.getX() + 36 * Math.cos(Math.toRadians(cartesianDegree));
		double predictionY = this.getY() + 36 * Math.sin(Math.toRadians(cartesianDegree));
		if (predictionX < 18 || predictionX > fieldWidth - 18 || predictionY < 18 || predictionY > fieldHeight - 18) {
			if (randomdegree > 0) {
				turnDegree = Utils.normalRelativeAngleDegrees(turnDegree - 200);
			} else {
				turnDegree = Utils.normalRelativeAngleDegrees(turnDegree + 200);
			}
		}
		//backした方が回転数を抑えられる場合はturnDegreeを180度反転させ、back。robocodeの角度系で計算したのでcompactMoveは使わない
		if (turnDegree < -90 || turnDegree > 90) {
			turnDegree = Utils.normalRelativeAngleDegrees(turnDegree + 180);
			this.turnRight(turnDegree);
			this.ahead(-36);
		} else {
			this.turnRight(turnDegree);
			this.ahead(36);
		}
	}

	@Override
	public void onHitWall(HitWallEvent event) {
		double degree = Utils.normalRelativeAngleDegrees(event.getBearing() + 90);
		this.turnRight(degree);
		this.ahead(56);
	}

	@Override
	public void onScannedRobot(ScannedRobotEvent event) {
		//敵情報を記録
		enemys.putIfAbsent(event.getName(), new Enemy());
		Enemy en = enemys.get(event.getName());
		en.distance = event.getDistance();
		en.name = event.getName();
		//初回のスキャンまたは前回スキャンから2ターン以上時間の空いたスキャンではacc(加速度)を0と記録
		if (en.time == 0 || this.getTime() - en.time >= 2) {
			en.acc = 0;
			en.deg = fixDegreeToCartesian(event.getHeading());
			en.x = this.getX() + en.distance
					* Math.cos(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
			en.y = this.getY() + en.distance
					* Math.sin(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
			en.vel = event.getVelocity();
			//速度(robocodeではバック移動をマイナス値で表現しているが、ここではプラスに変えて敵角度を180度反転することで表現する)
			if (en.vel < 0) {
				en.vel *= -1;
				en.deg = (en.deg + 180) % 360;
			}
			en.time = this.getTime();
			en.energy = event.getEnergy();
		} else {
			//このターンの速度-前のターンの速度＝加速度
			en.acc = Math.abs(event.getVelocity()) - en.vel;
			double tmpX = this.getX() + en.distance
					* Math.cos(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
			double tmpY = this.getY() + en.distance
					* Math.sin(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
			en.vel = event.getVelocity();
			en.deg = fixDegreeToCartesian(event.getHeading());
			//速度(robocodeではバック移動をマイナス値で表現しているが、ここではプラスに変えて敵角度を180度反転することで表現する)
			if (en.vel < 0) {
				en.vel *= -1;
				en.deg = (en.deg + 180) % 360;
			}
			//敵が速度最大または速度0を超えて加減速するようなら加速度を調整
			if (en.vel + en.acc > 8) {
				en.acc = 8 - en.vel;
			} else if (en.vel + en.acc < 0) {
				en.acc = 0 - en.vel;
			}
			en.x = tmpX;
			en.y = tmpY;
			en.time = this.getTime();
			en.energy = event.getEnergy();
		}
		//今lockonしている敵がいなければこの敵をlockon
		if (!lockOnMode) {
			lockOnMode = true;
			lockOnEnemy = en;
		}
		//lockonしている敵がいるならより距離の近い方をlockOn
		else if (lockOnEnemy.distance > en.distance) {
			lockOnEnemy = en;
		}
	}

	public void initAny() {
		this.setBodyColor(Color.BLACK);
		this.setGunColor(Color.BLACK);
		this.setRadarColor(Color.BLACK);
		this.setBulletColor(Color.WHITE);
		this.setScanColor(Color.BLACK);
		fieldWidth = getBattleFieldWidth();
		fieldHeight = getBattleFieldHeight();
		bulletPower = 3.0;
		previousFullScanTurn = 0;
		meleeMode = true;
		lockOnMode = false;
		lockOnEnemy = null;
		//敵情報の初期化(名前とgravPowerは保持)
		for (Enemy en : enemys.values()) {
			en.living = true;
			en.acc = java.lang.Double.NaN;
			en.energy = java.lang.Double.NaN;
			en.deg = java.lang.Double.NaN;
			en.time = 0;
			en.vel = java.lang.Double.NaN;
			en.x = java.lang.Double.NaN;
			en.y = java.lang.Double.NaN;
			en.distance = java.lang.Double.NaN;
		}

	}

	//robocodeの角度を普通の直交座標系に
	public double fixDegreeToCartesian(double robodeg) {
		return (90 - robodeg) % 360;
	}

	//普通の角度をrobocodeの角度系に
	public double fixDegreeToRobocode(double absdeg) {
		return (90 - absdeg) % 360;
	}

	//始点のx,yと終点のx,y座標から角度を求める
	public double getDegreeToIt(double x1, double y1, double x2, double y2) {
		return Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
	}

	//始点のx,yと終点のx,y座標から距離を求める
	public double getDistanceToIt(double x1, double y1, double x2, double y2) {
		return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
	}

	//敵が10体以上なら乱戦状態、9体以下ならfalse
	public void MeleeChecker() {
		if (this.getOthers() < 10) {
			meleeMode = false;
		}
	}

	//lockonしている敵を32turn見失ったらlockOnModeをfalseに
	public void lockingChecker() {
		if (lockOnMode) {
			if (this.getTime() - lockOnEnemy.time >= 32) {
				lockOnMode = false;
			}
		}
	}

	//100ターンごとに、100ターン以上姿の見えない敵を死んだとみなす
	public void enemyLivingChecker() {
		if (this.getTime() % 100 == 0) {
			long nowTime = this.getTime();
			for (Enemy en : enemys.values()) {
				if (en.time != 0 && nowTime - en.time >= 100) {
					en.living = false;
				}
			}
		}
	}

	//車体を回す角度(直交座標系で計算したもの)と移動距離を引数として、最適な移動をする
	public void compactMove(double headingTurnDegree, double length) {
		headingTurnDegree = Utils.normalRelativeAngleDegrees(headingTurnDegree);
		//backした方が回転数を抑えられる場合はheadingTurnDegreeを180度反転させ、back
		if (headingTurnDegree < -90 || headingTurnDegree > 90) {
			headingTurnDegree = Utils.normalRelativeAngleDegrees(headingTurnDegree + 180);
			this.turnLeft(headingTurnDegree);
			this.ahead(-length);
		} else {
			this.turnLeft(headingTurnDegree);
			this.ahead(length);
		}
	}

	//敵をlockoonしていないまたは乱戦中なら反重力運動、そうでなければlockonしている敵に対して適正な射撃距離を保つように動く
	public void moveLogic() {
		if (!lockOnMode || meleeMode) {
			double myHeading = fixDegreeToCartesian(this.getHeading()); //直交座標系での自分の向き
			double headingTurnDegree = 0; //最終的に機体を回転する角度

			double vectorDegree = antiGravityVector(); //反重力運動
			headingTurnDegree = vectorDegree - myHeading;
			compactMove(headingTurnDegree, 56); //56移動は12turn以内に移動できる最高距離
		}

		//敵との距離が近ければ反重力運動、中距離なら敵に対して直角に移動、遠ければ敵に近づく
		else if (lockOnEnemy.distance < 66) {
			double myHeading = fixDegreeToCartesian(this.getHeading()); //直交座標系での自分の向き
			double headingTurnDegreeInShort = 0; //最終的に機体を回転する角度

			double vectorDegree = antiGravityVector(); //反重力運動をするために算出した角度
			headingTurnDegreeInShort = vectorDegree - myHeading;
			compactMove(headingTurnDegreeInShort, 56);
		} else if (lockOnEnemy.distance < 330) {
			double myHeading = fixDegreeToCartesian(this.getHeading()); //直交座標系での自分の向き
			double enemyDegree = getDegreeToIt(this.getX(), this.getY(), lockOnEnemy.x, lockOnEnemy.y); //直交座標系での相手の角度
			double headingTurnDegreeInMedium; //最終的に機体を回転する角度

			//壁にぶつからないか計算し、ぶつかる場合はmediumDistanceMoveDegreeを符号反転し角度を再計算
			double predictionX = this.getX() + 56 * Math.cos(Math.toRadians(enemyDegree + mediumDistanceMoveDegree));
			double predictionY = this.getY() + 56 * Math.sin(Math.toRadians(enemyDegree + mediumDistanceMoveDegree));

			if (predictionX < 18 || predictionX > fieldWidth - 18 || predictionY < 18
					|| predictionY > fieldHeight - 18) {
				mediumDistanceMoveDegree *= -1;
				predictionX = this.getX() + 56 * Math.cos(Math.toRadians(enemyDegree + mediumDistanceMoveDegree));
				predictionY = this.getY() + 56 * Math.sin(Math.toRadians(enemyDegree + mediumDistanceMoveDegree));
				if (predictionX < 18 || predictionX > fieldWidth - 18 || predictionY < 18
						|| predictionY > fieldHeight - 18) {
					//それでもぶつかる場合は反重力運動
					double vectorDegree = antiGravityVector();
					compactMove(vectorDegree - myHeading, 56);
					return;
				}
			}
			headingTurnDegreeInMedium = enemyDegree - myHeading + mediumDistanceMoveDegree;
			compactMove(headingTurnDegreeInMedium, 56);

		} else {
			double myHeading = fixDegreeToCartesian(this.getHeading()); //直交座標系での自分の向き
			double enemyDegree = getDegreeToIt(this.getX(), this.getY(), lockOnEnemy.x, lockOnEnemy.y); //直交座標系での相手の角度
			double headingTurnDegreeInLong = enemyDegree - myHeading + 20; //最終的に機体を回転する角度

			//壁にぶつからないか計算し、ぶつかる場合は敵に対してつける角度を-20に変更
			double predictionX = this.getX() + 56 * Math.cos(Math.toRadians(enemyDegree + 20));
			double predictionY = this.getY() + 56 * Math.sin(Math.toRadians(enemyDegree + 20));

			if (predictionX < 18 || predictionX > fieldWidth - 18 || predictionY < 18
					|| predictionY > fieldHeight - 18) {
				headingTurnDegreeInLong = enemyDegree - myHeading - 20;
			}

			compactMove(headingTurnDegreeInLong, 56);
		}
	}

	//前回の360度スキャンから70ターン以上間が空いていれば360度スキャン
	public void fullScanAround() {
		if (this.getTime() - previousFullScanTurn >= 70) {
			this.turnRadarLeft(360);
			previousFullScanTurn = this.getTime();
		}
	}

	//lockOnModeがtrueである限り、Lockonした敵に確実に2回レーダーを当ててから射撃するを繰り返す
	public void lockOnRun() {
		this.setScanColor(Color.RED);
		while (lockOnMode) {
			double radarBearing = fixDegreeToRobocode(
					getDegreeToIt(this.getX(), this.getY(), lockOnEnemy.x, lockOnEnemy.y))
					- this.getRadarHeading(); //敵のいた角度 - 自分のレーダー角度
			double turnDegree = Utils.normalRelativeAngleDegrees(radarBearing);
			//turnDegreeの符号を保存
			boolean turnDegreeSignPlus = false;
			boolean turnDegreeSignMinus = false;
			if (turnDegree >= 0) {
				turnDegreeSignPlus = true;
			} else {
				turnDegreeSignMinus = true;
			}
			//lockonした敵の情報が更新されるまで探す。敵がすでに死んでいた場合は見つけようがないので、11回転して見つからなければlockonを外して出てくる
			int count = 0;
			while (this.getTime() - lockOnEnemy.time > 1) {
				//lockonした敵の過去の位置を参考にレーダーの回す向きを決め、45ずつ回す
				if (turnDegreeSignPlus) {
					this.turnRadarRight(45);
				} else {
					this.turnRadarRight(-45);
				}
				if (count >= 11) {
					lockOnMode = false;
					return;
				}
				count += 1;
			}
			//2回目を当てる
			//敵に掠るように当たってしまった場合を考える(再計算したturnDegreeの符号は敵を通り過ぎていれば反転するが、変化なければギリギリを掠っている)
			radarBearing = fixDegreeToRobocode(getDegreeToIt(this.getX(), this.getY(), lockOnEnemy.x, lockOnEnemy.y))
					- this.getRadarHeading(); //敵のいた角度 - 自分のレーダー角度
			turnDegree = Utils.normalRelativeAngleDegrees(radarBearing);
			if ((turnDegree >= 0 && turnDegreeSignPlus) || (turnDegree < 0 && turnDegreeSignMinus)) {
				//さっきと同じ方向に回す
				if (turnDegreeSignPlus) {
					this.turnRadarRight(45);
				} else {
					this.turnRadarRight(-45);
				}
			} else {
				//さっきと逆方向に回す
				if (turnDegreeSignPlus) {
					this.turnRadarRight(-45);
				} else {
					this.turnRadarRight(45);
				}
			}
			lockingChecker();
			enemyLivingChecker();
			MeleeChecker();
			predictionSnipe(lockOnEnemy);
			moveLogic();
			fullScanAround();
		}
		this.setScanColor(Color.BLACK);

	}

	//敵が等加速度直線運動をしていると仮定して予測射撃
	public void predictionSnipe(Enemy en) {
		//GunHeatが0.1以下でなければreturn
		if (this.getGunHeat() > 0.1) {
			return;
		}
		double myX = this.getX();
		double myY = this.getY();
		double enemyX = 0;
		double enemyY = 0;
		double btPower = bulletPower; //射撃のパワー
		//敵までの距離が330より大きければbtPowerを1/2
		if (en.distance > 330) {
			btPower = btPower / 2;
			if (btPower < 0.1) {
				btPower = 0.1;
			}
		}
		//自分のenergyが3.0以下であればbtPowerを0.1に
		if (this.getEnergy() <= 3.0) {
			btPower = 0.1;
		}
		double gunHeading = fixDegreeToCartesian(this.getGunHeading()); //直交座標系でのgunHeading
		double gunTurnTime = 0; //予測座標にgunを向けるのにかかる時間
		double bulletGoingTime = 0; //予測座標に射撃したと仮定したときの弾の飛ぶ時間
		double previousBulletGoingTime = 0; //予測ターンの一つ前のターンでのbulletGoingTime。そのターンと一つ前のターンでabs（弾飛距離-敵距離)を比較するときに使用

		// 1~100までの敵予測位置に対して射撃が間に合うものを探し、見つかった時点で発射してbreak
		for (int i = 1; i <= 100; i++) {
			enemyX = predictionX(en, i);
			enemyY = predictionY(en, i);
			gunTurnTime = Math.ceil(Math.abs(
					((Utils.normalRelativeAngleDegrees(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading)) / 20)));
			previousBulletGoingTime = bulletGoingTime;
			//予測座標に射撃したと仮定したときの弾の飛ぶ時間 = ターン数 - Gunの回転時間
			bulletGoingTime = i - gunTurnTime;
			//gunが回りきらなければcontinue
			if (bulletGoingTime <= 0) {
				continue;
			}
			//弾速*bulletGoingTime＝弾の飛距離が敵との距離を超えていれば
			if ((20 - 3 * btPower) * bulletGoingTime >= getDistanceToIt(myX, myY, enemyX, enemyY)) {
				//turn数 == 1 なら即発射
				if (i == 1) {
					this.turnGunLeft(
							Utils.normalRelativeAngleDegrees(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading));
					this.fire(btPower);
					break;
				} else {
					double previousEnemyX = predictionX(en, i - 1);
					double previousEnemyY = predictionY(en, i - 1);
					double previousBulletAndEnemyDifference = Math
							.abs((20 - 3 * btPower) * previousBulletGoingTime
									- getDistanceToIt(myX, myY, previousEnemyX, previousEnemyY));
					//gunの回転との兼ね合いによっては一つ前のターンでの予測位置の方が適している場合もあるので、
					//そのターンと一つ前のターンで弾飛距離-敵距離の絶対値を比較し、より敵距離に近い方を採用し発射
					if (Math.abs((20 - 3 * btPower) * bulletGoingTime - getDistanceToIt(myX, myY, enemyX, enemyY))
							- previousBulletAndEnemyDifference >= 0) {
						this.turnGunLeft(
								Utils.normalRelativeAngleDegrees(
										getDegreeToIt(myX, myY, previousEnemyX, previousEnemyY) - gunHeading));
						this.fire(btPower);
						break;
					} else {
						this.turnGunLeft(
								Utils.normalRelativeAngleDegrees(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading));
						this.fire(btPower);
						break;
					}
				}
			}
		}
	}

	//timeターン後の敵X座標を予測
	public double predictionX(Enemy en, double time) {
		//敵の変位。後でcosをかける
		double s = 0;

		double v = en.vel;
		double a = en.acc;
		double t = time;
		for (int i = 2; i < 2; i++) {
			//加速度が整数でないものは、その次のターンですぐ整数の値に更新されてしまい誤差の原因となるため、別途計算する
			if (a % 1 != 0 && t >= 1) {
				//変位 = vt + 1/2at^2 + 1/2at
				s += v + a;
				v += a;
				if (a < 0) {
					a = -2.0;
				} else {
					a = 1.0;
				}
				//敵が速度最大または速度0を超えて加減速するようなら加速度を調整
				if (v + a > 8) {
					a = 8 - v;
				} else if (v + a < 0) {
					a = 0 - v;
				}
				t -= 1;
			} else {
				break;
			}
		}
		while (v != 0 && t >= 1) {
			//変位 = vt + 1/2at^2 + 1/2at
			s += v + a;
			v += a;
			//敵が速度最大または速度0を超えて加減速するようなら加速度を調整
			if (v + a > 8) {
				a = 8 - v;
			} else if (v + a < 0) {
				a = 0 - v;
			}
			t -= 1;
			//速度が8になった時点で等速直線運動として処理
			if (v == 8 && t >= 1) {
				s += v * t;
				break;
			}
		}
		s *= Math.cos(Math.toRadians(en.deg));
		s += en.x;
		//壁を突き抜けないように修正
		if (s < 18) {
			s = 0 + 18;
		} else if (s > fieldWidth - 18) {
			s = fieldWidth - 18;
		}
		return s;
	}

	//timeターン後の敵Y座標を予測
	public double predictionY(Enemy en, double time) {
		//敵の変位。後でsinをかける
		double s = 0;

		double v = en.vel;
		double a = en.acc;
		double t = time;
		for (int i = 2; i < 2; i++) {
			//加速度が整数でないものは、その次のターンですぐ整数の値に更新されてしまい誤差の原因となるため、別途計算する
			if (a % 1 != 0 && t >= 1) {
				//変位 = vt + 1/2at^2 + 1/2at
				s += v + a;
				if (a < 0) {
					a = -2.0;
				} else {
					a = 1.0;
				}
				v += a;
				//敵が速度最大または速度0を超えて加減速するようなら加速度を調整
				if (v + a > 8) {
					a = 8 - v;
				} else if (v + a < 0) {
					a = 0 - v;
				}
				t -= 1;
			} else {
				break;
			}
		}
		while (v != 0 && t >= 1) {
			//変位 = vt + 1/2at^2 + 1/2at
			s += v + a;
			v += a;
			//敵が速度最大または速度0を超えて加減速するようなら加速度を調整
			if (v + a > 8) {
				a = 8 - v;
			} else if (v + a < 0) {
				a = 0 - v;
			}
			t -= 1;
			//速度が8になった時点で等速直線運動として処理
			if (v == 8 && t >= 1) {
				s += v * t;
				break;
			}
		}
		s *= Math.sin(Math.toRadians(en.deg));
		s += en.y;
		//壁を突き抜けないように修正
		if (s < 18) {
			s = 0 + 18;
		} else if (s > fieldHeight - 18) {
			s = fieldHeight - 18;
		}
		return s;
	}

	//敵と壁の斥力を距離減衰を考慮してからベクトル合成して移動すべき角度を求める。戻り値は直交座標系での最終的なベクトルの角度
	public double antiGravityVector() {
		double myX = this.getX();
		double myY = this.getY();
		double force = 0; //途中式で使う敵が自分に及ぼす斥力ベクトルの大きさ
		double enemyRadian = 0; //途中式で使う自分から見た敵の角度
		double componentX = 0; //自分が受けている斥力のx成分合計
		double componentY = 0; //自分が受けている斥力のy成分合計
		double vectorDegree = 0; // 最終的なベクトルの角度

		//全敵のgravPower（斥力）をベクトル合成
		if (!enemys.isEmpty()) { //ここの条件判定は無くてもエラーにはならない
			for (Enemy en : enemys.values()) {
				//敵が生きていなかったら飛ばす
				if (!en.living) {
					continue;
				}
				//敵x座標またはy座標にNaNが入っていたら飛ばす
				if (java.lang.Double.isNaN(en.x) || java.lang.Double.isNaN(en.y)) {
					continue;
				}
				//敵の斥力を距離の2乗で割ったものをx,y成分に分解し合計していく
				force = en.gravPower / Math.pow(getDistanceToIt(myX, myY, en.x, en.y), 2);
				//forceが負の無限大にいったときはcontinue(例:過去の敵記録位置と自分の現在位置がぴったり重なった場合)
				if (java.lang.Double.isInfinite(force)) {
					continue;
				}
				enemyRadian = Math.toRadians(getDegreeToIt(myX, myY, en.x, en.y));
				//forceはマイナス値であるため、この計算で正しいベクトルの成分になる
				componentX += force * Math.cos(enemyRadian);
				componentY += force * Math.sin(enemyRadian);
			}
		}

		//左壁から反時計回りに壁4面の斥力を距離の2乗で割ったものを合成
		componentX += 1000 / Math.pow(myX, 2); //左壁
		componentY += 1000 / Math.pow(myY, 2); //下壁
		componentX += -1000 / Math.pow(fieldWidth - myX, 2); //右壁
		componentY += -1000 / Math.pow(fieldHeight - myY, 2); //上壁

		vectorDegree = Math.toDegrees(Math.atan2(componentY, componentX));
		return vectorDegree;
	}
}
