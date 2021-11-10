/*
 * コメントにたびたび「直交座標系での角度」と出てくるものの、正しくは直交座標系での座標に対応する極座標上での角度である。
 * 要するにrobocodeの角度系と区別しているということを意味するためのもの。
 */

package myRobots;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import robocode.AdvancedRobot;
import robocode.BattleEndedEvent;
import robocode.BulletHitEvent;
import robocode.DeathEvent;
import robocode.GunTurnCompleteCondition;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.RoundEndedEvent;
import robocode.ScannedRobotEvent;
import robocode.SkippedTurnEvent;
import robocode.WinEvent;
import robocode.util.Utils;

public class WhiteAngel extends AdvancedRobot {
	//以下はconfig。この機体の機能を制限したい場合は対応する変数をfalseに
	final boolean enableAccuracyPrinting = false; //各射撃の精度をコンソールに出力
	final boolean enableMoving = true; //trueなら動く
	final boolean enableSnipe = true; //trueなら射撃を行う

	//自分の行動予定を表すクラス
	class MySchedule {
		double time;
		double x;
		double y;
		double turnDegree; //直交座標系
	}

	//敵情報を格納するクラス
	class Enemy implements Cloneable {
		String name;
		boolean living = true; //敵が生きているか
		double gravPower = -700; //この敵が持つ斥力。反重力運動に使う
		long time = 0; //情報を取得したターン
		double energy = Double.NaN;
		double x = Double.NaN;
		double y = Double.NaN;
		double displacementX = Double.NaN; //x座標変位
		double displacementY = Double.NaN; //y座標変位
		double vel = Double.NaN; //速度(robocodeではバック移動をマイナス値で表現しているが、ここではプラスに変えて敵角度を180度反転することで表現する)
		double acc = Double.NaN; //加速度
		double deg = Double.NaN; //敵ロボットの角度(停止時は頭の向き。敵が移動していればどの方向に移動しているかを直交座標系で)
		boolean degFixed = false; //角度の反転処理をしていたらtrue
		double angularVel = Double.NaN; //角速度
		double distance = Double.NaN;
		LinkedList<double[]> recentLog = new LinkedList<double[]>();

		@Override
		public Enemy clone() {
			Enemy cloneEn = null;
			try {
				cloneEn = (Enemy) super.clone();
			} catch (Exception e) {
				e.printStackTrace();
			}
			return cloneEn;
		}
	}

	//敵の予測弾丸情報を格納するクラス
	class EnemyBullet {
		String name;
		double remainTurn; //弾の有効期限。このターンを過ぎた弾は削除される
		double startX;
		double startY;
		double targetX;
		double targetY;
		double bulletPower;
		double bulletDegree; //弾の直交座標系での角度
		double gravPower = 200;
	}

	//予測射撃の精度を知るための仮想的な弾を格納するクラス
	class VirtualMyBullet {
		String targetName;
		String snipingType; //どの射撃パターンを使ったか
		long hittingTime; //このターンに当たると予想
		double x;
		double y;
	}

	class SnipingAccuracy {
		int linerSnipingCount = 0; //射撃回数(当たったかどうか確認できたもののみ)
		double linerPredictionHittingCount = 0; //当たった回数
		double linerPredictionRate = ACCURACY_BORDER; //射撃制度
		int circularSnipingCount = 0; //射撃回数(当たったかどうか確認できたもののみ)
		double circularPredictionHittingCount = 0; //当たった回数
		double circularPredictionRate = ACCURACY_BORDER; //射撃制度
		int averageDisplacementSnipingCount = 0; //射撃回数(当たったかどうか確認できたもののみ)
		double averageDisplacementPredictionHittingCount = 0; //当たった回数
		double averageDisplacementPredictionRate = ACCURACY_BORDER; //射撃制度
		int repetitiveSnipingCount = 0; //射撃回数(当たったかどうか確認できたもののみ)
		double repetitivePredictionHittingCount = 0; //当たった回数
		double repetitivePredictionRate = ACCURACY_BORDER; //射撃制度
		int degPatternSnipingCount = 0; //射撃回数(当たったかどうか確認できたもののみ)
		double degPatternPredictionHittingCount = 0; //当たった回数
		double degPatternPredictionRate = ACCURACY_BORDER; //射撃制度
		int angularVelPatternSnipingCount = 0; //射撃回数(当たったかどうか確認できたもののみ)
		double angularVelPatternPredictionHittingCount = 0; //当たった回数
		double angularVelPatternPredictionRate = ACCURACY_BORDER; //射撃制度
	}

	double fieldWidth;
	double fieldHeight;
	double BorderGuardLine;
	double maxBulletPower = 3.0; //弾のpower
	double maxVelocity = 8.0; //最大速度
	final double ACCURACY_BORDER = 0.05; //射撃をする最低精度
	boolean scheduleLock = false; //これがtrueの間は新たなahead()やturn()をセットしない(敵や壁にぶつかった場合を除く)
	boolean meleeMode = true; //今が乱戦状態ならtrue
	boolean oneOnOneMode = false; //1対1ならtrue
	boolean lockOnMode = false; //敵をロックオンしているならtrue
	boolean readyToFire = false; //trueならそのターンはsetFire
	MySchedule nextMe = null; //次ターンの自分の行動
	Enemy lockOnEnemy = null; //攻撃する敵
	Enemy wantedEnemy = null; //指名手配している敵。この敵を優先してロックオンする
	//Enemyクラス群を管理するMap
	HashMap<String, Enemy> enemysMap = new HashMap<String, Enemy>();
	//Enemyのログ群を管理するMap。ログは毎ターンLinkedListに保存していき、それをArraysListに追加、1turnでも見失った場合はその時点で一区切りし新しくLinkedListを作成して記録していく
	//LinkedListがひとかたまりのログで、それを集めたのがArrayList
	HashMap<String, ArrayList<LinkedList<double[]>>> enemyLogsMap = new HashMap<String, ArrayList<LinkedList<double[]>>>();
	//敵の弾を格納するlist
	LinkedList<EnemyBullet> enemysBulletsList = new LinkedList<EnemyBullet>();
	//直近50turnのこちらに攻撃を当ててきた敵を格納するlist
	LinkedList<Enemy> hitByBulletList = new LinkedList<Enemy>();
	//自分の仮想的な弾を格納するlist
	LinkedList<VirtualMyBullet> virtualBulletsList = new LinkedList<VirtualMyBullet>();
	//各敵に対する射撃の精度を格納するMap
	HashMap<String, SnipingAccuracy> snipingAccuraciesMap = new HashMap<String, SnipingAccuracy>();

	final int RECENT_LOGSIZE = 7; //敵の直近のログのサイズ
	Random random = Utils.getRandom();
	double randomNum = random.nextDouble(); //ランダム値。10turnごとに更新

	@Override
	public void onPaint(Graphics2D g) {
		for (VirtualMyBullet bt : virtualBulletsList) {
			if (bt.snipingType.equals("liner")) {
				g.setColor(Color.RED);
			} else if (bt.snipingType.equals("circular")) {
				g.setColor(Color.GREEN);
			} else if (bt.snipingType.equals("averageDisplacement")) {
				g.setColor(Color.CYAN);
			} else if (bt.snipingType.equals("repetitive")) {
				g.setColor(Color.PINK);
			} else if (bt.snipingType.equals("degPattern")) {
				g.setColor(Color.BLUE);
			} else if (bt.snipingType.equals("angularVelPattern")) {
				g.setColor(Color.YELLOW);
			}
			g.drawOval((int) bt.x, (int) bt.y, 3, 3);

		}
	}

	@Override
	public void run() {
		initAny();
		while (true) {
			if (!lockOnMode) {
				MeleeChecker();
				enemyLivingChecker();
				randomNumUpdate();
				this.setTurnRadarLeft(65);
				this.setTurnGunLeft(20);
				whitelyAngelMoving();
				this.execute();
			} else {
				MeleeChecker();
				enemyLivingChecker();
				lockingChecker();
				randomNumUpdate();
				whitelyAngelMoving();
				whitelyAngelSniping(lockOnEnemy);
				this.execute();
			}
		}
	}

	@Override
	public void onWin(WinEvent event) {

	}

	@Override
	public void onDeath(DeathEvent event) {

	}

	@Override
	public void onBattleEnded(BattleEndedEvent event) {

	}

	@Override
	public void onRoundEnded(RoundEndedEvent event) {
	}

	@Override
	public void onSkippedTurn(SkippedTurnEvent event) {
		for (int i = 0; i < 50; i++) {
			System.out.println("WARNING!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
		}
	}

	@Override
	public void onHitByBullet(HitByBulletEvent event) {
		long currentTime = this.getTime();

		//敵情報を記録
		enemysMap.putIfAbsent(event.getName(), new Enemy());
		Enemy en = enemysMap.get(event.getName());
		en.name = event.getName();
		en.energy += event.getPower() * 3;

		//単純にこちらへ向かって撃たれたと仮定したときの弾道を保存
		EnemyBullet enbt = new EnemyBullet();
		enbt.bulletPower = event.getPower();
		//敵が次弾をGunHeatが0になり次第撃っていると仮定して予想到達ターン-1を有効期限とする
		double nextBulletInterval = (1.0 + maxBulletPower / 5) / this.getGunCoolingRate();
		enbt.remainTurn = this.getTime() + nextBulletInterval - 1;
		enbt.name = en.name;
		enbt.targetX = this.getX();
		enbt.targetY = this.getY();
		enbt.bulletDegree = fixDegreeToCartesian(event.getBearing() + this.getHeading() + 180);
		//敵の位置はわからないので、適当に350px離れていることにする
		enbt.startX = 350 * Math.cos(Math.toRadians(enbt.bulletDegree - 180));
		enbt.startY = 350 * Math.sin(Math.toRadians(enbt.bulletDegree - 180));
		//borderGuardの弾は斥力を小さめに設定
		if (en.name.matches(".*samplesentry.*")) {
			enbt.gravPower = 1;
		}
		enemysBulletsList.add(enbt);

		Enemy hitByBulletEnemy = en.clone();
		hitByBulletEnemy.time = currentTime;
		hitByBulletList.add(hitByBulletEnemy);

		Enemy wantedEn = null;
		//50turn以上経過したhitByBulletListの敵を削除
		Iterator<Enemy> hitByBulletIterator = hitByBulletList.iterator();
		while (hitByBulletIterator.hasNext()) {
			Enemy tmpEnemy = hitByBulletIterator.next();
			if (currentTime - tmpEnemy.time > 50) {
				hitByBulletIterator.remove();
			}
			//直近50turn以内に2回射撃されているかつBorderGuardでないかつ敵が30体未満の場合はwantedEnemyに指定
			else if (tmpEnemy.name.equals(en.name) && !en.name.matches(".*BorderGuard.*") && this.getOthers() < 30) {
				wantedEn = en.clone();
			}
		}

		if (wantedEn != null) {
			lockOnMode = false;
			wantedEn.time = currentTime;
			wantedEnemy = wantedEn;
		}

	}

	@Override
	public void onBulletHit(BulletHitEvent event) {
		//敵情報を記録
		enemysMap.putIfAbsent(event.getName(), new Enemy());
		Enemy en = enemysMap.get(event.getName());
		en.name = event.getName();
		//弾のダメージを敵energyから引く
		en.energy -= (event.getBullet().getPower() * 4) + (Math.max(0, event.getBullet().getPower() - 1) * 2);
		//敵HPが0ならliving=False
		if (en.energy <= 0) {
			en.living = false;
			enemyLogsMap.get(en.name).clear();
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
		double enemyBearing = event.getBearing();
		//敵情報を記録
		enemysMap.putIfAbsent(event.getName(), new Enemy());
		Enemy en = enemysMap.get(event.getName());
		en.name = event.getName();
		en.vel = 0;
		en.acc = 0;
		//敵と当たった場合はたいてい斜めに当たっているため、距離を40としておく
		en.x = this.getX()
				+ 40 * Math.cos(Math.toRadians(fixDegreeToCartesian(enemyBearing + this.getHeading())));
		en.y = this.getY()
				+ 40 * Math.sin(Math.toRadians(fixDegreeToCartesian(enemyBearing + this.getHeading())));
		en.energy = event.getEnergy();
		en.distance = 36; //ここは反重力運動にかかわるため、36と短めに
		en.living = true;

		//samplesentryはwantedEnemyに指定しない。敵が30体以上いる場合も指定しない
		if (en.name.matches(".*samplesentry.*") || this.getOthers() >= 30) {
			return;
		}
		lockOnMode = false;
		//優先的にこの敵を狙う
		Enemy wantedEn = en.clone();
		wantedEn.time = this.getTime();
		wantedEnemy = wantedEn;
	}

	@Override
	public void onHitWall(HitWallEvent event) {
		System.out.println("WALLL!!!!!!!");
	}

	@Override
	public void onScannedRobot(ScannedRobotEvent event) {
		//敵情報を記録
		enemysMap.putIfAbsent(event.getName(), new Enemy());
		enemyLogsMap.putIfAbsent(event.getName(), new ArrayList<LinkedList<double[]>>());
		Enemy en = enemysMap.get(event.getName());
		en.distance = event.getDistance();
		en.name = event.getName();

		//初回のスキャンまたは前回スキャンから2ターン以上時間の空いたスキャンではacc(加速度)と角速度を0と記録し、ログリストを再作成
		if (en.time == 0 || this.getTime() - en.time != 1) {
			en.acc = 0;
			en.angularVel = 0;
			en.deg = fixDegreeToCartesian(event.getHeading());
			en.x = this.getX() + en.distance
					* Math.cos(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
			en.y = this.getY() + en.distance
					* Math.sin(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
			en.vel = event.getVelocity();
			//速度(robocodeではバック移動をマイナス値で表現しているが、ここではプラスに変えて敵角度を180度反転することで表現する)
			if (en.vel < 0) {
				en.vel *= -1;
				en.deg = en.deg + 180;
				en.degFixed = true;
			} else {
				en.degFixed = false;
			}
			en.time = this.getTime();
			en.energy = event.getEnergy();
			if (en.energy == 0) {
				en.living = false;
			} else {
				en.living = true;
			}
			ArrayList<LinkedList<double[]>> enLogs = enemyLogsMap.get(event.getName());
			enLogs.add(new LinkedList<double[]>());
			//直近のログをリセット
			en.recentLog.clear();
			en.recentLog.add(new double[3]);
			en.recentLog.get(0)[0] = en.vel;
			en.recentLog.get(0)[1] = en.deg;
			en.recentLog.get(0)[2] = en.angularVel;
		} else {
			//このターンの速度-前のターンの速度＝加速度
			en.acc = Math.abs(event.getVelocity()) - en.vel;
			double tmpX = this.getX() + en.distance
					* Math.cos(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
			double tmpY = this.getY() + en.distance
					* Math.sin(Math.toRadians(fixDegreeToCartesian(event.getBearing() + this.getHeading())));
			en.displacementX = tmpX - en.x;
			en.displacementY = tmpY - en.y;
			en.x = tmpX;
			en.y = tmpY;
			double enemyEnergyChange = en.energy - event.getEnergy();
			//敵が何かと衝突して急停止した時を除いて0.1以上3.0以下のエネルギー消費をしていた場合、こちらに向かって射撃したとみなす
			if (en.acc >= -2.0 && 0.099 <= enemyEnergyChange && enemyEnergyChange <= 3.01) {
				this.setBodyColor(Color.BLACK);
				//単純にこちらへ向かって撃たれたと仮定したときの弾道を保存
				EnemyBullet enbt = new EnemyBullet();
				enbt.bulletPower = enemyEnergyChange;
				//予想到達ターン-1を有効期限とする
				double bulletSpeed = 20 - 3 * enbt.bulletPower;
				enbt.remainTurn = this.getTime() + Math.ceil((en.distance - bulletSpeed) / bulletSpeed) - 1;
				enbt.name = en.name;
				enbt.startX = en.x;
				enbt.startY = en.y;
				enbt.targetX = this.getX();
				enbt.targetY = this.getY();
				enbt.bulletDegree = getDegreeToIt(enbt.startX, enbt.startY, enbt.targetX, enbt.targetY);
				//borderGuardの弾は斥力を小さめに設定
				if (en.name.matches(".*samplesentry.*")) {
					enbt.gravPower = 1;
				}
				enemysBulletsList.add(enbt);
			} else {
				this.setBodyColor(Color.WHITE);
			}
			en.energy = event.getEnergy();
			if (en.energy == 0) {
				en.living = false;
			} else {
				en.living = true;
			}
			//角速度計算
			if (en.degFixed) {
				//前回で角度が反転処理されていたら、元に戻してから計算
				en.angularVel = event.getHeading() - fixDegreeToRobocode(en.deg - 180);
			} else {
				en.angularVel = event.getHeading() - fixDegreeToRobocode(en.deg);
			}
			//直交座標系の角速度に
			en.angularVel *= -1;
			//0度付近で計算していると1度-359度などと計算されてしまうので、直す
			if (en.angularVel >= 349) {
				en.angularVel -= 360;
			} else if (en.angularVel <= -349) {
				en.angularVel += 360;
			}
			en.vel = event.getVelocity();
			en.deg = fixDegreeToCartesian(event.getHeading());
			//速度(robocodeではバック移動をマイナス値で表現しているが、ここではプラスに変えて敵角度を180度反転することで表現する)
			if (en.vel < 0) {
				en.vel *= -1;
				en.deg = en.deg + 180;
				en.degFixed = true;
			} else {
				en.degFixed = false;
			}
			//敵が速度最大または速度0を超えて加減速するようなら加速度を調整
			if (en.vel + en.acc > 8) {
				en.acc = 8 - en.vel;
			} else if (en.vel + en.acc < 0) {
				en.acc = 0 - en.vel;
			}
			en.time = this.getTime();
			//ログを保存
			ArrayList<LinkedList<double[]>> enLogs = enemyLogsMap.get(event.getName());
			if (enLogs.isEmpty()) {
				enLogs.add(new LinkedList<double[]>());
			}
			LinkedList<double[]> logList = enLogs.get(enLogs.size() - 1);
			logList.add(new double[3]);
			logList.get(logList.size() - 1)[0] = en.vel;
			logList.get(logList.size() - 1)[1] = en.deg;
			logList.get(logList.size() - 1)[2] = en.angularVel;
			//直近のログを保存
			en.recentLog.add(new double[3]);
			en.recentLog.get(en.recentLog.size() - 1)[0] = en.vel;
			en.recentLog.get(en.recentLog.size() - 1)[1] = en.deg;
			en.recentLog.get(en.recentLog.size() - 1)[2] = en.angularVel;
			//直近のログがいっぱいなら、最も古いものを削除
			if (en.recentLog.size() > RECENT_LOGSIZE) {
				en.recentLog.removeFirst();
			}
		}

		//ラウンド開始28ターンはロックオンしない
		if (en.time <= 28) {
			return;
		}

		//sentryRobotをlockonしている場合はlockonをはずす
		if (lockOnMode) {
			if (en.name.equals(lockOnEnemy.name) && event.isSentryRobot()) {
				lockOnMode = false;
			}
		}

		//wantedEnemyがいる場合はそれ以外の敵をlockonしない
		if (wantedEnemy != null) {
			if (en.name.equals(wantedEnemy.name)) {
				wantedEnemy = null;
				if (!event.isSentryRobot()) {
					lockOnEnemy = en;
				}
			} else {
				return;
			}
		}
		//sentryRobotに対してlockOn処理をしない
		if (event.isSentryRobot()) {
			return;
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
		//lockonしている敵にレーダーを当て続ける
		if (lockOnMode && en.name.equals(lockOnEnemy.name)) {
			spotRight();
		}
	}

	public void initAny() {
		this.setBodyColor(Color.WHITE);
		this.setGunColor(Color.WHITE);
		this.setRadarColor(Color.WHITE);
		this.setBulletColor(Color.WHITE);
		this.setScanColor(Color.WHITE);
		this.setAdjustGunForRobotTurn(true);
		this.setAdjustRadarForGunTurn(true);
		fieldWidth = getBattleFieldWidth();
		fieldHeight = getBattleFieldHeight();
		BorderGuardLine = this.getSentryBorderSize();
		scheduleLock = false;
		meleeMode = true;
		oneOnOneMode = false;
		lockOnMode = false;
		readyToFire = false;
		enemysBulletsList.clear();
		lockOnEnemy = null;
		wantedEnemy = null;
		MySchedule schedule = new MySchedule();
		schedule.time = this.getTime();
		schedule.turnDegree = 0;
		schedule.x = this.getX();
		schedule.y = this.getY();
		nextMe = schedule;
	}

	//robocodeの角度を普通の直交座標系に
	public double fixDegreeToCartesian(double robodeg) {
		return 90 - robodeg;
	}

	//普通の角度をrobocodeの角度系に
	public double fixDegreeToRobocode(double absdeg) {
		return 90 - absdeg;
	}

	//始点のx,yと終点のx,y座標から角度を求める
	public double getDegreeToIt(double x1, double y1, double x2, double y2) {
		return Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
	}

	//敵が10体以上ならmelee=true、7体以下ならfalse。1対1ならoneOnOne=true
	public void MeleeChecker() {
		if (this.getOthers() < 8) {
			meleeMode = false;
			if (this.getOthers() == 1) {
				oneOnOneMode = true;
			}
		}
	}

	//lockonしている敵を11turn見失ったらlockOnModeをfalseに
	public void lockingChecker() {
		if (lockOnMode) {
			if (this.getTime() - lockOnEnemy.time >= 2) {
				lockOnMode = false;
			}
		}
	}

	//200ターンごとに、200ターン以上姿の見えない敵を死んだとみなす。また、7ターン見つけられないwantedEnemyを削除
	public void enemyLivingChecker() {
		long currentTime = this.getTime();
		if (this.getTime() % 200 == 0) {
			for (Enemy en : enemysMap.values()) {
				if (en.time != 0 && currentTime - en.time >= 100) {
					en.living = false;
				}
			}
		}
		if (wantedEnemy != null) {
			if (currentTime - wantedEnemy.time >= 7) {
				wantedEnemy = null;
			}
		}
	}

	//10turn毎にランダム値を更新
	public void randomNumUpdate() {
		if (this.getTime() % 10 == 0) {
			randomNum = random.nextDouble();
		}
	}

	//車体を回す角度(直交座標系で計算したもの)と移動距離を引数として、最適な移動をする
	public void compactMove(double headingTurnDegree, double length) {
		headingTurnDegree = Utils.normalRelativeAngleDegrees(headingTurnDegree);
		//backした方が回転数を抑えられる場合はheadingTurnDegreeを180度反転させ、back
		if (headingTurnDegree < -90 || headingTurnDegree > 90) {
			headingTurnDegree = Utils.normalRelativeAngleDegrees(headingTurnDegree + 180);
			this.setTurnLeft(headingTurnDegree);
			this.setAhead(-length);
			Scheduling(headingTurnDegree, -length);
		} else {
			this.setTurnLeft(headingTurnDegree);
			this.setAhead(length);
			Scheduling(headingTurnDegree, length);
		}
	}

	//これからの行動予定表を車体を回す角度(直交座標系で計算したもの)と移動距離を引数として作成
	public void Scheduling(double headingTurnDegree, double length) {
		scheduleLock = true;
		double currentTime = this.getTime();
		double currentVel = this.getVelocity();
		double currentHeading = fixDegreeToCartesian(this.getHeading()); //直交座標系
		double currentMaxTurnRate = 10 - 0.75 * Math.abs(currentVel);
		double nextVel;
		//このロボットの次ターンの速度を計算（あらゆる状況に対応する計算式ではないことに注意。このロボットに限って使用できる）
		if (length > 0) {
			//今:前進または停止、次:前進の場合
			if (currentVel >= 0) {
				nextVel = currentVel + 1.0;
				if (nextVel > maxVelocity) {
					nextVel = maxVelocity;
				}
			}
			//今:後退、次:前進の場合
			else {
				nextVel = currentVel + 2.0;
				if (nextVel > 0) {
					//robocodeでは2.0未満の減速で0に達した場合、余力を加速に回す処理が行われる
					nextVel = 0 + nextVel / 2;
				}
			}
		} else {
			//今:後退または停止、次:後退の場合
			if (currentVel <= 0) {
				nextVel = currentVel - 1.0;
				if (nextVel < -maxVelocity) {
					nextVel = -maxVelocity;
				}
			}
			//今:前進、次:後退の場合
			else {
				nextVel = currentVel - 2.0;
				if (nextVel < 0) {
					//robocodeでは2.0未満の減速で0に達した場合、余力を加速に回す処理が行われる
					nextVel = 0 + nextVel / 2;
				}
			}
		}

		MySchedule schedule = new MySchedule();
		schedule.time = currentTime + 1;
		schedule.turnDegree = headingTurnDegree;
		//回転量の最大値を超えていれば、最大値に丸める
		if (Math.abs(schedule.turnDegree) > currentMaxTurnRate) {
			schedule.turnDegree = currentMaxTurnRate * Math.signum(schedule.turnDegree);
		}
		schedule.x = this.getX() + nextVel * Math.cos(Math.toRadians(currentHeading + schedule.turnDegree));
		schedule.y = this.getY() + nextVel * Math.sin(Math.toRadians(currentHeading + schedule.turnDegree));
		nextMe = schedule;
	}

	//敵をlockoonしていないまたは乱戦中なら反重力運動、そうでなければlockonしている敵に対して適正な射撃距離を保つように動く
	public void whitelyAngelMoving() {
		//configの処理
		if (!enableMoving) {
			return;
		}

		double myHeading = fixDegreeToCartesian(this.getHeading()); //直交座標系での自分の向き
		double headingTurnDegree = 0; //最終的に機体を回転する角度

		double vectorDegree = antiGravityVector(); //反重力運動
		headingTurnDegree = vectorDegree - myHeading;
		compactMove(headingTurnDegree, Double.POSITIVE_INFINITY);

	}

	//lockonした敵を補足し続ける。
	public void spotRight() {
		double radarBearing = fixDegreeToRobocode(
				getDegreeToIt(nextMe.x, nextMe.y, linerPredictionX(lockOnEnemy, 1), linerPredictionY(lockOnEnemy, 1)))
				- this.getRadarHeading(); //敵の次座標までの角度 - 自分のレーダー角度
		radarBearing = Utils.normalRelativeAngleDegrees(radarBearing);
		double marginDegree = 22.5;
		double turnDegree;
		if (radarBearing >= 0) {
			turnDegree = radarBearing + marginDegree;
		} else {
			turnDegree = radarBearing - marginDegree;
		}
		this.setTurnRadarRight(turnDegree);
	}

	//射撃に関することを決める
	public void whitelyAngelSniping(Enemy en) {
		pattarnCleaning(en);
		snipingAccuracyAnalysis(en);

		//configの処理
		if (!enableSnipe) {
			return;
		}

		//readyToFireがtrueならこのturnに射撃が行われるので、何もせずreturn
		if (readyToFire) {
			readyToFire = false;
			return;
		}

		double btPower = maxBulletPower; //射撃のパワー

		//敵までの距離が330より大きくかつ敵が30体以下ならばbtPowerを2/3に
		if (en.distance > 330 && this.getOthers() <= 30) {
			btPower = (maxBulletPower / 3) * 2;
			if (btPower < 0.1) {
				btPower = 0.1;
			}
		}
		//自分のenergyが3.0以下であればbtPowerを0.1に
		if (this.getEnergy() <= 3.0) {
			btPower = 0.1;
		}

		//敵が超至近距離なら加速度予測で即発射
		if (en.distance < 70) {
			linerPredictionSnipe(en, btPower);
			return;
		}

		SnipingAccuracy accuracy = snipingAccuraciesMap.get(en.name);

		//各射撃typeの精度ベースでの降順リストを作成
		Map<String, Double> accuracyMap = new LinkedHashMap<String, Double>(6);
		accuracyMap.put("liner", accuracy.linerPredictionRate);
		accuracyMap.put("circular", accuracy.circularPredictionRate);
		accuracyMap.put("repetitive", accuracy.repetitivePredictionRate);
		accuracyMap.put("degPattern", accuracy.degPatternPredictionRate);
		accuracyMap.put("angularVelPattern", accuracy.angularVelPatternPredictionRate);
		accuracyMap.put("averageDisplacement", accuracy.averageDisplacementPredictionRate);

		List<Map.Entry<String, Double>> sortedAccuracyList = new ArrayList<Map.Entry<String, Double>>(
				accuracyMap.entrySet());
		Collections.sort(sortedAccuracyList, (o1, o2) -> o2.getValue().compareTo(o1.getValue()));

		for (Map.Entry<String, Double> entry : sortedAccuracyList) {
			if (entry.getKey().equals("circular") && entry.getValue() >= ACCURACY_BORDER) {
				boolean circularResult = CircularPredictionSnipe(en, btPower);
				if (circularResult) {
					return;
				} else {
					continue;
				}
			} else if (entry.getKey().equals("repetitive") && entry.getValue() >= ACCURACY_BORDER) {
				boolean repetitiveResult = repetitiveKillerSnipe(en, btPower);
				if (repetitiveResult) {
					return;
				} else {
					continue;
				}
			} else if (entry.getKey().equals("degPattern") && entry.getValue() >= ACCURACY_BORDER) {
				boolean degPatternResult = degPatternPredictionSnip(en, btPower);
				if (degPatternResult) {
					return;
				} else {
					continue;
				}
			} else if (entry.getKey().equals("angularVelPattern") && entry.getValue() >= ACCURACY_BORDER) {
				boolean angVelPatternResult = angularVelPatternPredictionSnip(en, btPower);
				if (angVelPatternResult) {
					return;
				} else {
					continue;
				}
			}
			//敵が一時停止を頻繁にするとlinerの射撃精度が0%～10%で乱高下するので、少し厳しめに最低精度を設定
			else if (entry.getKey().equals("liner") && entry.getValue() >= ACCURACY_BORDER) {
				linerPredictionSnipe(en, btPower);
				return;
			} else if (entry.getKey().equals("averageDisplacement") && entry.getValue() >= ACCURACY_BORDER) {
				boolean averageDisplacementResult = averageDisplacementSnipe(en, btPower);
				if (averageDisplacementResult) {
					return;
				} else {
					continue;
				}
			}
		}

		//射撃精度が十分でなかったとき用の処理。敵が30体以下ならbtPwerを0.1にして加速度予測射撃
		if (this.getOthers() <= 30) {
			btPower = 0.1;
		}
		linerPredictionSnipe(en, btPower);
	}

	//射撃の精度を計測
	public void snipingAccuracyAnalysis(Enemy en) {
		long currentTime = this.getTime();
		final int ALLOWABLE_RANGE = 5; //許される誤差
		final int UPDATE_INTERVAL = 15; //射撃精度を更新する頻度
		final double BOTTOM_ACCURACY_RATE = 0.1;
		snipingAccuraciesMap.putIfAbsent(en.name, new SnipingAccuracy());
		SnipingAccuracy accuracy = snipingAccuraciesMap.get(en.name);

		//virtualBulletsListから過去この敵に対して作成したものを取り出し、当たったかどうか評価
		Iterator<VirtualMyBullet> virtualBulletsIterator = virtualBulletsList.iterator();
		while (virtualBulletsIterator.hasNext()) {
			VirtualMyBullet bt = virtualBulletsIterator.next();
			//評価されずに時間が過ぎたものを削除
			if (bt.hittingTime < currentTime) {
				virtualBulletsIterator.remove();
			}
			if (bt.targetName.equals(en.name) && bt.hittingTime == en.time) {
				//当たったかどうか判別し、UPDATE_INTERVALturn分集まった時点で射撃精度を更新
				//射撃精度の更新は古い精度と新しい精度を半々に足したもの。
				double difference = Math.hypot(en.x - bt.x, en.y - bt.y);
				if (bt.snipingType.equals("liner")) {
					accuracy.linerSnipingCount += 1;
					if (difference <= ALLOWABLE_RANGE) {
						accuracy.linerPredictionHittingCount += 1;
					}
					//UPDATE_INTERVALturn分の情報が集まったら射撃精度を更新し、リセット
					if (accuracy.linerSnipingCount >= UPDATE_INTERVAL) {
						accuracy.linerPredictionRate = accuracy.linerPredictionRate / 2
								+ (accuracy.linerPredictionHittingCount
										/ accuracy.linerSnipingCount) / 2;
						if (accuracy.linerPredictionRate < BOTTOM_ACCURACY_RATE) {
							accuracy.linerPredictionRate = 0;
						}
						accuracy.linerSnipingCount = 0;
						accuracy.linerPredictionHittingCount = 0;
					}
				} else if (bt.snipingType.equals("circular")) {
					accuracy.circularSnipingCount += 1;
					if (difference <= ALLOWABLE_RANGE) {
						accuracy.circularPredictionHittingCount += 1;
					}
					//UPDATE_INTERVALturn分の情報が集まったら射撃精度を更新し、リセット
					if (accuracy.circularSnipingCount >= UPDATE_INTERVAL) {
						accuracy.circularPredictionRate = accuracy.circularPredictionRate / 2
								+ (accuracy.circularPredictionHittingCount
										/ accuracy.circularSnipingCount) / 2;
						if (accuracy.circularPredictionRate < BOTTOM_ACCURACY_RATE) {
							accuracy.circularPredictionRate = 0;
						}
						accuracy.circularSnipingCount = 0;
						accuracy.circularPredictionHittingCount = 0;
					}
				} else if (bt.snipingType.equals("averageDisplacement")) {
					accuracy.averageDisplacementSnipingCount += 1;
					if (difference <= ALLOWABLE_RANGE) {
						accuracy.averageDisplacementPredictionHittingCount += 1;
					}
					//UPDATE_INTERVALturn分の情報が集まったら射撃精度を更新し、リセット
					if (accuracy.averageDisplacementSnipingCount >= UPDATE_INTERVAL) {
						accuracy.averageDisplacementPredictionRate = accuracy.averageDisplacementPredictionRate / 2
								+ (accuracy.averageDisplacementPredictionHittingCount
										/ accuracy.averageDisplacementSnipingCount) / 2;
						if (accuracy.averageDisplacementPredictionRate < BOTTOM_ACCURACY_RATE) {
							accuracy.averageDisplacementPredictionRate = 0;
						}
						accuracy.averageDisplacementSnipingCount = 0;
						accuracy.averageDisplacementPredictionHittingCount = 0;
					}
				} else if (bt.snipingType.equals("repetitive")) {
					accuracy.repetitiveSnipingCount += 1;
					if (difference <= ALLOWABLE_RANGE) {
						accuracy.repetitivePredictionHittingCount += 1;
					}
					//UPDATE_INTERVALturn分の情報が集まったら射撃精度を更新し、リセット
					if (accuracy.repetitiveSnipingCount >= UPDATE_INTERVAL) {
						accuracy.repetitivePredictionRate = accuracy.repetitivePredictionRate / 2
								+ (accuracy.repetitivePredictionHittingCount
										/ accuracy.repetitiveSnipingCount) / 2;
						//System.out.println("rate" + (accuracy.repetitivePredictionHittingCount
						/// accuracy.repetitiveSnipingCount));
						if (accuracy.repetitivePredictionRate < BOTTOM_ACCURACY_RATE) {
							accuracy.repetitivePredictionRate = 0;
						}
						accuracy.repetitiveSnipingCount = 0;
						accuracy.repetitivePredictionHittingCount = 0;
					}
				} else if (bt.snipingType.equals("degPattern")) {
					accuracy.degPatternSnipingCount += 1;
					if (difference <= ALLOWABLE_RANGE) {
						accuracy.degPatternPredictionHittingCount += 1;
					}
					//UPDATE_INTERVALturn分の情報が集まったら射撃精度を更新し、リセット
					if (accuracy.degPatternSnipingCount >= UPDATE_INTERVAL) {
						accuracy.degPatternPredictionRate = accuracy.degPatternPredictionRate / 2
								+ (accuracy.degPatternPredictionHittingCount
										/ accuracy.degPatternSnipingCount) / 2;
						if (accuracy.degPatternPredictionRate < BOTTOM_ACCURACY_RATE) {
							accuracy.degPatternPredictionRate = 0;
						}
						accuracy.degPatternSnipingCount = 0;
						accuracy.degPatternPredictionHittingCount = 0;
					}
				} else if (bt.snipingType.equals("angularVelPattern")) {
					accuracy.angularVelPatternSnipingCount += 1;
					if (difference <= ALLOWABLE_RANGE) {
						accuracy.angularVelPatternPredictionHittingCount += 1;
					}
					//UPDATE_INTERVALturn分の情報が集まったら射撃精度を更新し、リセット
					if (accuracy.angularVelPatternSnipingCount >= UPDATE_INTERVAL) {
						accuracy.angularVelPatternPredictionRate = accuracy.angularVelPatternPredictionRate / 2
								+ (accuracy.angularVelPatternPredictionHittingCount
										/ accuracy.angularVelPatternSnipingCount) / 2;
						if (accuracy.angularVelPatternPredictionRate < BOTTOM_ACCURACY_RATE) {
							accuracy.angularVelPatternPredictionRate = 0;
						}
						accuracy.angularVelPatternSnipingCount = 0;
						accuracy.angularVelPatternPredictionHittingCount = 0;
					}
				}
			}
		}

		//configの処理
		if (enableAccuracyPrinting && currentTime % UPDATE_INTERVAL == 0) {
			System.out.println("-----Turn: " + currentTime + " name:" + en.name + "-----");
			System.out.println("liner: " + (accuracy.linerPredictionRate * 100) + "%");
			System.out.println("circular: " + (accuracy.circularPredictionRate * 100) + "%");
			System.out.println("averageDisplacement: " + (accuracy.averageDisplacementPredictionRate * 100) + "%");
			System.out.println("repetitive: " + (accuracy.repetitivePredictionRate * 100) + "%");
			System.out.println("degPattern: " + (accuracy.degPatternPredictionRate * 100) + "%");
			System.out.println("angVelPattern: " + (accuracy.angularVelPatternPredictionRate * 100) + "%");
		}

		//敵にPower2.0の弾を撃った場合に当たるかを予測(Gunの回転は考慮しない)
		final int PREDICTION_TURN = (int) Math.round(en.distance / (20 - 3 * 2.0));
		VirtualMyBullet linerBt = new VirtualMyBullet();
		linerBt.hittingTime = currentTime + PREDICTION_TURN;
		linerBt.targetName = en.name;
		linerBt.snipingType = "liner";
		linerBt.x = linerPredictionX(en, PREDICTION_TURN);
		linerBt.y = linerPredictionY(en, PREDICTION_TURN);
		virtualBulletsList.add(linerBt);

		VirtualMyBullet circularBt = new VirtualMyBullet();
		circularBt.hittingTime = currentTime + PREDICTION_TURN;
		circularBt.targetName = en.name;
		circularBt.snipingType = "circular";
		LinkedList<double[]> circularList = circularPredictionList(en);
		if (circularList != null) {
			double[] circularPredictionXY = circularList.get(PREDICTION_TURN - 1);
			circularBt.x = circularPredictionXY[0];
			circularBt.y = circularPredictionXY[1];
			virtualBulletsList.add(circularBt);
		} else {
			circularBt.x = Double.NaN;
			circularBt.y = Double.NaN;
			virtualBulletsList.add(circularBt);
		}

		LinkedList<double[]> sampleLogData = sampleLogMaker(en);
		//パターンが集まりきっていない場合は当たっていないことにする
		if (!adequateRecentLogJudge(en) || sampleLogData == null) {
			VirtualMyBullet averageDisplacementBt = new VirtualMyBullet();
			averageDisplacementBt.hittingTime = currentTime + PREDICTION_TURN;
			averageDisplacementBt.targetName = en.name;
			averageDisplacementBt.snipingType = "averageDisplacement";
			averageDisplacementBt.x = Double.NaN;
			averageDisplacementBt.y = Double.NaN;
			virtualBulletsList.add(averageDisplacementBt);

			VirtualMyBullet repetitiveBt = new VirtualMyBullet();
			repetitiveBt.hittingTime = currentTime + PREDICTION_TURN;
			repetitiveBt.targetName = en.name;
			repetitiveBt.snipingType = "repetitive";
			repetitiveBt.x = Double.NaN;
			repetitiveBt.y = Double.NaN;
			virtualBulletsList.add(repetitiveBt);

			VirtualMyBullet degPatternBt = new VirtualMyBullet();
			degPatternBt.hittingTime = currentTime + PREDICTION_TURN;
			degPatternBt.targetName = en.name;
			degPatternBt.snipingType = "degPattern";
			degPatternBt.x = Double.NaN;
			degPatternBt.y = Double.NaN;
			virtualBulletsList.add(degPatternBt);

			VirtualMyBullet angularVelPatternBt = new VirtualMyBullet();
			angularVelPatternBt.hittingTime = currentTime + PREDICTION_TURN;
			angularVelPatternBt.targetName = en.name;
			angularVelPatternBt.snipingType = "angularVelPattern";
			angularVelPatternBt.x = Double.NaN;
			angularVelPatternBt.y = Double.NaN;
			virtualBulletsList.add(angularVelPatternBt);
			return;
		}

		VirtualMyBullet averageDisplacementBt = new VirtualMyBullet();
		averageDisplacementBt.hittingTime = currentTime + PREDICTION_TURN;
		averageDisplacementBt.targetName = en.name;
		averageDisplacementBt.snipingType = "averageDisplacement";
		final int PATTERN_SIZE = 20;
		LinkedList<double[]> recentList = recentLogList(en, PATTERN_SIZE);

		if (recentList != null) {
			double averageDisplacementX = 0;
			double averageDisplacementY = 0;
			Iterator<double[]> recentListIterator = recentList.iterator();
			while (recentListIterator.hasNext()) {
				double[] tmpNext = recentListIterator.next();
				averageDisplacementX += tmpNext[0] * Math.cos(Math.toRadians(tmpNext[1]));
				averageDisplacementY += tmpNext[0] * Math.sin(Math.toRadians(tmpNext[1]));
			}
			averageDisplacementX /= PATTERN_SIZE;
			averageDisplacementY /= PATTERN_SIZE;

			double averageDisplacementPredictionEnemyX = en.x;
			double averageDisplacementPredictionEnemyY = en.y;
			for (int i = 0; i < PREDICTION_TURN; i++) {
				averageDisplacementPredictionEnemyX += averageDisplacementX;
				averageDisplacementPredictionEnemyY += averageDisplacementY;
				averageDisplacementPredictionEnemyX = overWallAdjustingX(averageDisplacementPredictionEnemyX);
				averageDisplacementPredictionEnemyY = overWallAdjustingY(averageDisplacementPredictionEnemyY);
			}
			averageDisplacementBt.x = averageDisplacementPredictionEnemyX;
			averageDisplacementBt.y = averageDisplacementPredictionEnemyY;
			virtualBulletsList.add(averageDisplacementBt);
		} else {
			averageDisplacementBt.x = Double.NaN;
			averageDisplacementBt.y = Double.NaN;
			virtualBulletsList.add(averageDisplacementBt);
		}

		VirtualMyBullet repetitiveBt = new VirtualMyBullet();
		repetitiveBt.hittingTime = currentTime + PREDICTION_TURN;
		repetitiveBt.targetName = en.name;
		repetitiveBt.snipingType = "repetitive";
		int repeListSize = repetitiveListSize(en, sampleLogData);
		if (repeListSize != -1) {
			LinkedList<double[]> repeList = recentRepetitiveList(en, repeListSize);
			if (repeList != null) {
				Iterator<double[]> repeListIterator = repeList.iterator();

				double repetitivePredictionEnemyX = en.x;
				double repetitivePredictionEnemyY = en.y;
				for (int i = 0; i < PREDICTION_TURN; i++) {
					if (repeListIterator.hasNext()) {
						double[] log = repeListIterator.next();
						repetitivePredictionEnemyX += log[0] * Math.cos(Math.toRadians(log[1]));
						repetitivePredictionEnemyY += log[0] * Math.sin(Math.toRadians(log[1]));
						repetitivePredictionEnemyX = overWallAdjustingX(repetitivePredictionEnemyX);
						repetitivePredictionEnemyY = overWallAdjustingY(repetitivePredictionEnemyY);
					} else {
						repeListIterator = repeList.iterator();
						double[] log = repeListIterator.next();
						repetitivePredictionEnemyX += log[0] * Math.cos(Math.toRadians(log[1]));
						repetitivePredictionEnemyY += log[0] * Math.sin(Math.toRadians(log[1]));
						repetitivePredictionEnemyX = overWallAdjustingX(repetitivePredictionEnemyX);
						repetitivePredictionEnemyY = overWallAdjustingY(repetitivePredictionEnemyY);
					}
				}
				repetitiveBt.x = repetitivePredictionEnemyX;
				repetitiveBt.y = repetitivePredictionEnemyY;
				virtualBulletsList.add(repetitiveBt);
			} else {
				repetitiveBt.x = Double.NaN;
				repetitiveBt.y = Double.NaN;
				virtualBulletsList.add(repetitiveBt);
			}

		} else {
			repetitiveBt.x = Double.NaN;
			repetitiveBt.y = Double.NaN;
			virtualBulletsList.add(repetitiveBt);
		}

		VirtualMyBullet degPatternBt = new VirtualMyBullet();
		degPatternBt.hittingTime = currentTime + PREDICTION_TURN;
		degPatternBt.targetName = en.name;
		degPatternBt.snipingType = "degPattern";
		int degPatternMatchedIndex = degPatternMatchingIndex(en, sampleLogData);
		if (degPatternMatchedIndex != -1) {
			Iterator<double[]> logIterator = sampleLogData.iterator();
			//予測ターンの前まではnext()で飛ばす
			for (int i = 0; i < degPatternMatchedIndex; i++) {
				logIterator.next();
			}
			double degPatternPredictionEnemyX = en.x;
			double degPatternPredictionEnemyY = en.y;
			for (int i = 0; i < PREDICTION_TURN; i++) {
				double[] log = logIterator.next();
				degPatternPredictionEnemyX += log[0] * Math.cos(Math.toRadians(log[1]));
				degPatternPredictionEnemyY += log[0] * Math.sin(Math.toRadians(log[1]));
				degPatternPredictionEnemyX = overWallAdjustingX(degPatternPredictionEnemyX);
				degPatternPredictionEnemyY = overWallAdjustingY(degPatternPredictionEnemyY);
			}
			degPatternBt.x = degPatternPredictionEnemyX;
			degPatternBt.y = degPatternPredictionEnemyY;
			virtualBulletsList.add(degPatternBt);
		} else {
			degPatternBt.x = Double.NaN;
			degPatternBt.y = Double.NaN;
			virtualBulletsList.add(degPatternBt);
		}

		VirtualMyBullet angularVelPatternBt = new VirtualMyBullet();
		angularVelPatternBt.hittingTime = currentTime + PREDICTION_TURN;
		angularVelPatternBt.targetName = en.name;
		angularVelPatternBt.snipingType = "angularVelPattern";
		int angularVelPatternMatchedIndex = angularVelPatternMatchingIndex(en, sampleLogData);
		if (angularVelPatternMatchedIndex != -1) {
			Iterator<double[]> logIterator = sampleLogData.iterator();
			//予測ターンの前まではnext()で飛ばす
			for (int i = 0; i < angularVelPatternMatchedIndex; i++) {
				logIterator.next();
			}
			double angVelPatternPredictionEnemyX = en.x;
			double angVelPatternPredictionEnemyY = en.y;
			double enemyDeg = en.deg;
			for (int i = 0; i < PREDICTION_TURN; i++) {
				double[] log = logIterator.next();
				enemyDeg += log[2];
				angVelPatternPredictionEnemyX += log[0] * Math.cos(Math.toRadians(enemyDeg));
				angVelPatternPredictionEnemyY += log[0] * Math.sin(Math.toRadians((enemyDeg)));
				angVelPatternPredictionEnemyX = overWallAdjustingX(angVelPatternPredictionEnemyX);
				angVelPatternPredictionEnemyY = overWallAdjustingY(angVelPatternPredictionEnemyY);
			}
			angularVelPatternBt.x = angVelPatternPredictionEnemyX;
			angularVelPatternBt.y = angVelPatternPredictionEnemyY;
			virtualBulletsList.add(angularVelPatternBt);
		} else {
			angularVelPatternBt.x = Double.NaN;
			angularVelPatternBt.y = Double.NaN;
			virtualBulletsList.add(angularVelPatternBt);
		}
	}

	public void getReadyToSnipe(double gunTurnDegree, double btPower) {
		gunTurnDegree = Utils.normalRelativeAngleDegrees(gunTurnDegree);
		//次ターンの車体回転角から時計回りと反時計回りのgunの最大回転角を求める。
		double counterClockWiseMax = 20 + nextMe.turnDegree;
		double clockWiseMax = 20 - nextMe.turnDegree;

		this.setTurnGunLeft(gunTurnDegree);
		if (gunTurnDegree >= 0) {
			//Gunの回転が1turnの内に終わらないorGunHeatが0にならない場合は、gunの回転のみを行う
			if (gunTurnDegree <= counterClockWiseMax && this.getGunHeat() <= 0.1) {
				this.waitFor(new GunTurnCompleteCondition(this));
				this.setFire(btPower);
				MeleeChecker();
				enemyLivingChecker();
				lockingChecker();
				randomNumUpdate();
				whitelyAngelMoving();
				readyToFire = true;
			}
		} else {
			if (Math.abs(gunTurnDegree) <= clockWiseMax && this.getGunHeat() <= 0.1) {
				this.waitFor(new GunTurnCompleteCondition(this));
				this.setFire(btPower);
				MeleeChecker();
				enemyLivingChecker();
				lockingChecker();
				randomNumUpdate();
				whitelyAngelMoving();
				readyToFire = true;
			}
		}
	}

	//敵が等加速度直線運動をしていると仮定して予測射撃
	public void linerPredictionSnipe(Enemy en, double btPower) {
		this.setBulletColor(Color.RED);
		double myX = nextMe.x;
		double myY = nextMe.y;
		double enemyX;
		double enemyY;
		double previousEnemyX = en.x;
		double previousEnemyY = en.y;
		double gunHeading = fixDegreeToCartesian(this.getGunHeading()); //直交座標系でのgunHeading
		double gunTurnTime = 0; //予測座標にgunを向けるのにかかる時間
		double bulletGoingTime = 0; //予測座標に射撃したと仮定したときの弾の飛ぶ時間
		double previousBulletGoingTime = 0; //予測ターンの一つ前のターンでのbulletGoingTime。そのターンと一つ前のターンでabs（弾飛距離-敵距離)を比較するときに使用

		// 1~100までの敵予測位置に対して射撃が間に合うものを探し、見つかった時点でbreak
		LinkedList<double[]> predictionList = linerPredictionList(en);
		Iterator<double[]> predictionListIterator = predictionList.iterator();
		for (int i = 1; i <= 100; i++) {
			double[] next = predictionListIterator.next();
			enemyX = next[0];
			enemyY = next[1];
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
			if ((20 - 3 * btPower) * bulletGoingTime >= Math.hypot(enemyX - myX, enemyY - myY)) {
				//turn数 == 1 なら即発射
				if (i == 1) {
					getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
					break;
				} else {
					double previousBulletAndEnemyDifference = Math
							.abs((20 - 3 * btPower) * previousBulletGoingTime
									- Math.hypot(previousEnemyX - myX, previousEnemyY - myY));
					//gunの回転との兼ね合いによっては一つ前のターンでの予測位置の方が適している場合もあるので、
					//そのターンと一つ前のターンで弾飛距離-敵距離の絶対値を比較し、より敵距離に近い方を採用し発射
					if (Math.abs((20 - 3 * btPower) * bulletGoingTime - Math.hypot(enemyX - myX, enemyY - myY))
							- previousBulletAndEnemyDifference >= 0) {
						getReadyToSnipe(getDegreeToIt(myX, myY, previousEnemyX, previousEnemyY) - gunHeading, btPower);
						break;
					} else {
						getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
						break;
					}
				}
			}
			previousEnemyX = enemyX;
			previousEnemyY = enemyY;
		}
	}

	//timeターン後の敵X座標を予測
	public double linerPredictionX(Enemy en, double time) {
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
				t--;
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
			t--;
			//速度が8になった時点で等速直線運動として処理
			if (v == 8 && t >= 1) {
				s += v * t;
				break;
			}
		}
		s *= Math.cos(Math.toRadians(en.deg));
		s += en.x;
		//壁を突き抜けないように修正
		s = overWallAdjustingX(s);
		return s;
	}

	//timeターン後の敵Y座標を予測
	public double linerPredictionY(Enemy en, double time) {
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
				t--;
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
			t--;
			//速度が8になった時点で等速直線運動として処理
			if (v == 8 && t >= 1) {
				s += v * t;
				break;
			}
		}
		s *= Math.sin(Math.toRadians(en.deg));
		s += en.y;
		//壁を突き抜けないように修正
		s = overWallAdjustingY(s);
		return s;
	}

	//100turn分の加速度予測座標を返す
	public LinkedList<double[]> linerPredictionList(Enemy en) {
		LinkedList<double[]> predictionList = new LinkedList<double[]>();
		double[] buffer = new double[2];
		//敵の変位
		double s = 0;
		double x = en.x;
		double y = en.y;
		double predictionX;
		double predictionY;

		double v = en.vel;
		double a = en.acc;
		double t = 100;
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
				predictionX = x + s * Math.cos(Math.toRadians(en.deg));
				predictionX = overWallAdjustingX(predictionX);
				predictionY = y + s * Math.sin(Math.toRadians(en.deg));
				predictionY = overWallAdjustingY(predictionY);
				buffer[0] = predictionX;
				buffer[1] = predictionY;
				predictionList.add(buffer.clone());
				t--;
			} else {
				break;
			}
		}
		while (t >= 1) {
			//変位 = vt + 1/2at^2 + 1/2at
			s += v + a;
			v += a;
			//敵が速度最大または速度0を超えて加減速するようなら加速度を調整
			if (v + a > 8) {
				a = 8 - v;
			} else if (v + a < 0) {
				a = 0 - v;
			}
			predictionX = x + s * Math.cos(Math.toRadians(en.deg));
			predictionX = overWallAdjustingX(predictionX);
			predictionY = y + s * Math.sin(Math.toRadians(en.deg));
			predictionY = overWallAdjustingY(predictionY);
			buffer[0] = predictionX;
			buffer[1] = predictionY;
			predictionList.add(buffer.clone());
			t--;
			//速度が8or0になった時点で等速直線運動として処理
			if ((v == 8 || v == 0) && t >= 1) {
				while (t >= 1) {
					s += v;
					predictionX = x + s * Math.cos(Math.toRadians(en.deg));
					predictionX = overWallAdjustingX(predictionX);
					predictionY = y + s * Math.sin(Math.toRadians(en.deg));
					predictionY = overWallAdjustingY(predictionY);
					buffer[0] = predictionX;
					buffer[1] = predictionY;
					predictionList.add(buffer.clone());
					t--;
				}
				break;
			}
		}
		return predictionList;
	}

	//敵が等速円形運動をしていると仮定して予測射撃
	public boolean CircularPredictionSnipe(Enemy en, double btPower) {
		this.setBulletColor(Color.GREEN);
		double myX = nextMe.x;
		double myY = nextMe.y;
		double enemyX;
		double enemyY;
		double previousEnemyX = en.x;
		double previousEnemyY = en.y;
		double gunHeading = fixDegreeToCartesian(this.getGunHeading()); //直交座標系でのgunHeading
		double gunTurnTime = 0; //予測座標にgunを向けるのにかかる時間
		double bulletGoingTime = 0; //予測座標に射撃したと仮定したときの弾の飛ぶ時間
		double previousBulletGoingTime = 0; //予測ターンの一つ前のターンでのbulletGoingTime。そのターンと一つ前のターンでabs（弾飛距離-敵距離)を比較するときに使用

		LinkedList<double[]> predictionList = circularPredictionList(en);
		if (predictionList == null) {
			return false;
		}
		// 1~100までの敵予測位置に対して射撃が間に合うものを探し、見つかった時点でbreak
		Iterator<double[]> predictionListIterator = predictionList.iterator();
		for (int i = 1; i <= 100; i++) {
			double[] next = predictionListIterator.next();
			enemyX = next[0];
			enemyY = next[1];
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
			if ((20 - 3 * btPower) * bulletGoingTime >= Math.hypot(enemyX - myX, enemyY - myY)) {
				//turn数 == 1 なら即発射
				if (i == 1) {
					getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
					return true;
				} else {
					double previousBulletAndEnemyDifference = Math
							.abs((20 - 3 * btPower) * previousBulletGoingTime
									- Math.hypot(previousEnemyX - myX, previousEnemyY - myY));
					//gunの回転との兼ね合いによっては一つ前のターンでの予測位置の方が適している場合もあるので、
					//そのターンと一つ前のターンで弾飛距離-敵距離の絶対値を比較し、より敵距離に近い方を採用し発射
					if (Math.abs((20 - 3 * btPower) * bulletGoingTime - Math.hypot(enemyX - myX, enemyY - myY))
							- previousBulletAndEnemyDifference >= 0) {
						getReadyToSnipe(getDegreeToIt(myX, myY, previousEnemyX, previousEnemyY) - gunHeading, btPower);
						return true;
					} else {
						getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
						return true;
					}
				}
			}
			previousEnemyX = enemyX;
			previousEnemyY = enemyY;
		}
		return false;
	}

	//円形予測
	public LinkedList<double[]> circularPredictionList(Enemy en) {
		int listSize = 100;
		//角速度がほぼ0のときは予測しない
		if (Math.abs(en.angularVel) < 0.001) {
			return null;
		}
		LinkedList<double[]> predictionList = new LinkedList<double[]>();
		//敵の角度を角速度分回転させながら、等速円運動として予測
		double nextEnemyDeg = en.deg + en.angularVel;
		double[] nextEnemyXY = new double[2];
		nextEnemyXY[0] = en.x + en.vel * Math.cos(Math.toRadians(nextEnemyDeg));
		nextEnemyXY[1] = en.y + en.vel * Math.sin(Math.toRadians(nextEnemyDeg));
		nextEnemyXY[0] = overWallAdjustingX(nextEnemyXY[0]);
		nextEnemyXY[1] = overWallAdjustingY(nextEnemyXY[1]);
		predictionList.add(nextEnemyXY.clone());
		listSize--;

		while (listSize > 0) {
			nextEnemyDeg += en.angularVel;
			nextEnemyXY[0] += en.vel * Math.cos(Math.toRadians(nextEnemyDeg));
			nextEnemyXY[1] += en.vel * Math.sin(Math.toRadians(nextEnemyDeg));
			nextEnemyXY[0] = overWallAdjustingX(nextEnemyXY[0]);
			nextEnemyXY[1] = overWallAdjustingY(nextEnemyXY[1]);
			predictionList.add(nextEnemyXY.clone());
			listSize--;
		}

		return predictionList;
	}

	//敵の直近30turnの変位を使って予測射撃
	public boolean averageDisplacementSnipe(Enemy en, double btPower) {
		final int PATTERN_SIZE = 20;
		if (!adequateRecentLogJudge(en)) {
			return false;
		}
		LinkedList<double[]> recentList = recentLogList(en, PATTERN_SIZE);
		if (recentList == null) {
			return false;
		}
		double averageDisplacementX = 0;
		double averageDisplacementY = 0;
		Iterator<double[]> recentListIterator = recentList.iterator();
		while (recentListIterator.hasNext()) {
			double[] tmpNext = recentListIterator.next();
			averageDisplacementX += tmpNext[0] * Math.cos(Math.toRadians(tmpNext[1]));
			averageDisplacementY += tmpNext[0] * Math.sin(Math.toRadians(tmpNext[1]));
		}
		averageDisplacementX /= PATTERN_SIZE;
		averageDisplacementY /= PATTERN_SIZE;

		this.setBulletColor(Color.CYAN);
		double myX = nextMe.x;
		double myY = nextMe.y;
		double enemyX = en.x;
		double enemyY = en.y;
		double previousEnemyX = en.x;
		double previousEnemyY = en.y;
		double gunHeading = fixDegreeToCartesian(this.getGunHeading()); //直交座標系でのgunHeading
		double gunTurnTime = 0; //予測座標にgunを向けるのにかかる時間
		double bulletGoingTime = 0; //予測座標に射撃したと仮定したときの弾の飛ぶ時間
		double previousBulletGoingTime = 0; //予測ターンの一つ前のターンでのbulletGoingTime。そのターンと一つ前のターンでabs（弾飛距離-敵距離)を比較するときに使用

		// 1~100までの敵予測位置に対して射撃が間に合うものを探し、見つかった時点でbreak
		for (int i = 1; i <= 100; i++) {
			enemyX += averageDisplacementX;
			enemyY += averageDisplacementY;
			enemyX = overWallAdjustingX(enemyX);
			enemyY = overWallAdjustingY(enemyY);
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
			if ((20 - 3 * btPower) * bulletGoingTime >= Math.hypot(enemyX - myX, enemyY - myY)) {
				//turn数 == 1 なら即発射
				if (i == 1) {
					getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
					return true;
				} else {
					double previousBulletAndEnemyDifference = Math
							.abs((20 - 3 * btPower) * previousBulletGoingTime
									- Math.hypot(previousEnemyX - myX, previousEnemyY - myY));
					//gunの回転との兼ね合いによっては一つ前のターンでの予測位置の方が適している場合もあるので、
					//そのターンと一つ前のターンで弾飛距離-敵距離の絶対値を比較し、より敵距離に近い方を採用し発射
					if (Math.abs((20 - 3 * btPower) * bulletGoingTime - Math.hypot(enemyX - myX, enemyY - myY))
							- previousBulletAndEnemyDifference >= 0) {
						getReadyToSnipe(getDegreeToIt(myX, myY, previousEnemyX, previousEnemyY) - gunHeading, btPower);
						return true;
					} else {
						getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
						return true;
					}
				}
			}
			previousEnemyX = enemyX;
			previousEnemyY = enemyY;
		}
		return false;
	}

	//敵のx座標が壁を突き抜けていれば丸める
	public double overWallAdjustingX(double x) {
		//壁を突き抜けないように修正
		if (x < 18) {
			x = 0 + 18;
		} else if (x > fieldWidth - 18) {
			x = fieldWidth - 18;
		}
		return x;
	}

	//敵のy座標が壁を突き抜けていれば丸める
	public double overWallAdjustingY(double y) {
		//壁を突き抜けないように修正
		if (y < 18) {
			y = 0 + 18;
		} else if (y > fieldHeight - 18) {
			y = fieldHeight - 18;
		}
		return y;
	}

	//直近のログが最大になっているか判定
	public boolean adequateRecentLogJudge(Enemy en) {
		if (en.recentLog.size() < RECENT_LOGSIZE) {
			return false;
		} else {
			return true;
		}
	}

	//最も大きいサイズかつ最低限のサイズを持つログを返す
	public LinkedList<double[]> sampleLogMaker(Enemy en) {
		ArrayList<LinkedList<double[]>> logsList = enemyLogsMap.get(en.name);
		if (logsList.isEmpty() || (logsList.size() == 1 && logsList.get(0).size() < 30)) {
			return null;
		}
		LinkedList<double[]> sampleLogData = null;
		//最も大きいサイズのログをサンプルとして使う
		for (LinkedList<double[]> logList : logsList) {
			if (sampleLogData == null) {
				sampleLogData = logList;
			} else if (logList.size() > sampleLogData.size()) {
				sampleLogData = logList;
			}
		}
		return sampleLogData;
	}

	//反復運動キラー
	public boolean repetitiveKillerSnipe(Enemy en, double btPower) {
		if (!adequateRecentLogJudge(en)) {
			return false;
		}
		//直近の動きが直線運動でなければfalseを返す
		if (!linerMotionJudge(en)) {
			return false;
		}
		LinkedList<double[]> sampleLogData = sampleLogMaker(en);
		if (sampleLogData == null) {
			return false;
		}

		int repeListSize = repetitiveListSize(en, sampleLogData);
		if (repeListSize == -1) {
			//System.out.println("repetiList見つからない");
			return false;
		}

		LinkedList<double[]> repeList = recentRepetitiveList(en, repeListSize);
		if (repeList == null) {
			//System.out.println("recentrepetiList見つからない");
			return false;
		}
		//		System.out.println("time:" + this.getTime() + "-------------------------------");
		//		//		for (double[] ele : repeList) {
		//		//			System.out.println("vel:" + ele[0] + "deg:" + ele[1]);
		//		//		}
		//		System.out.println("repeSize:" + repeListSize);
		this.setBulletColor(Color.BLACK);

		Iterator<double[]> repeListIterator = repeList.iterator();
		double myX = nextMe.x;
		double myY = nextMe.y;
		double enemyX = en.x;
		double enemyY = en.y;
		double previousEnemyX = en.x;
		double previousEnemyY = en.y;

		double gunHeading = fixDegreeToCartesian(this.getGunHeading()); //直交座標系でのgunHeading
		double gunTurnTime = 0; //予測座標にgunを向けるのにかかる時間
		double bulletGoingTime = 0; //予測座標に射撃したと仮定したときの弾の飛ぶ時間
		double previousBulletGoingTime = 0; //予測ターンの一つ前のターンでのbulletGoingTime。そのターンと一つ前のターンでabs（弾飛距離-敵距離)を比較するときに使用

		// 100turnを超えるまでの敵予測位置に対して射撃が間に合うものを探し、見つかった時点でreturn
		for (int i = 0; i < 100; i++) {
			if (repeListIterator.hasNext()) {
				double[] log = repeListIterator.next();
				enemyX += log[0] * Math.cos(Math.toRadians(log[1]));
				enemyY += log[0] * Math.sin(Math.toRadians(log[1]));
				enemyX = overWallAdjustingX(enemyX);
				enemyY = overWallAdjustingY(enemyY);
			} else {
				repeListIterator = repeList.iterator();
				double[] log = repeListIterator.next();
				enemyX += log[0] * Math.cos(Math.toRadians(log[1]));
				enemyY += log[0] * Math.sin(Math.toRadians(log[1]));
				enemyX = overWallAdjustingX(enemyX);
				enemyY = overWallAdjustingY(enemyY);
			}
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
			if ((20 - 3 * btPower) * bulletGoingTime >= Math.hypot(enemyX - myX, enemyY - myY)) {
				//turn数 == 1 なら即発射
				if (i == 1) {
					getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
					return true;
				} else {
					double previousBulletAndEnemyDifference = Math
							.abs((20 - 3 * btPower) * previousBulletGoingTime
									- Math.hypot(previousEnemyX - myX, previousEnemyY - myY));
					//gunの回転との兼ね合いによっては一つ前のターンでの予測位置の方が適している場合もあるので、
					//そのターンと一つ前のターンで弾飛距離-敵距離の絶対値を比較し、より敵距離に近い方を採用し発射
					if (Math.abs((20 - 3 * btPower) * bulletGoingTime - Math.hypot(enemyX - myX, enemyY - myY))
							- previousBulletAndEnemyDifference >= 0) {
						getReadyToSnipe(getDegreeToIt(myX, myY, previousEnemyX, previousEnemyY) - gunHeading, btPower);
						return true;
					} else {
						getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
						return true;
					}
				}
			}
			previousEnemyX = enemyX;
			previousEnemyY = enemyY;
		}

		return false;
	}

	//角度パターン予測射撃
	public boolean degPatternPredictionSnip(Enemy en, double btPower) {
		if (!adequateRecentLogJudge(en)) {
			return false;
		}
		if (uniformDegLinerMotionJudge(en)) {
			return false;
		}
		LinkedList<double[]> sampleLogData = sampleLogMaker(en);
		if (sampleLogData == null) {
			return false;
		}

		int matchedIndex = degPatternMatchingIndex(en, sampleLogData); //敵ログのどの場所から予測開始するか
		if (matchedIndex == -1) {
			return false;
		}

		this.setBulletColor(Color.BLUE);
		double myX = nextMe.x;
		double myY = nextMe.y;
		double enemyX = en.x;
		double enemyY = en.y;
		double previousEnemyX = en.x;
		double previousEnemyY = en.y;

		double gunHeading = fixDegreeToCartesian(this.getGunHeading()); //直交座標系でのgunHeading
		double gunTurnTime = 0; //予測座標にgunを向けるのにかかる時間
		double bulletGoingTime = 0; //予測座標に射撃したと仮定したときの弾の飛ぶ時間
		double previousBulletGoingTime = 0; //予測ターンの一つ前のターンでのbulletGoingTime。そのターンと一つ前のターンでabs（弾飛距離-敵距離)を比較するときに使用

		Iterator<double[]> logIterator = sampleLogData.iterator();
		//予測ターンの前まではnext()で飛ばす
		for (int i = 0; i < matchedIndex; i++) {
			logIterator.next();
		}
		// 1~80turnまでの敵予測位置に対して射撃が間に合うものを探し、見つかった時点でreturn
		for (int i = 0; i < 70; i++) {
			double[] log = logIterator.next();
			enemyX += log[0] * Math.cos(Math.toRadians(log[1]));
			enemyY += log[0] * Math.sin(Math.toRadians(log[1]));
			enemyX = overWallAdjustingX(enemyX);
			enemyY = overWallAdjustingY(enemyY);
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
			if ((20 - 3 * btPower) * bulletGoingTime >= Math.hypot(enemyX - myX, enemyY - myY)) {
				//turn数 == 1 なら即発射
				if (i == 1) {
					getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
					return true;
				} else {
					double previousBulletAndEnemyDifference = Math
							.abs((20 - 3 * btPower) * previousBulletGoingTime
									- Math.hypot(previousEnemyX - myX, previousEnemyY - myY));
					//gunの回転との兼ね合いによっては一つ前のターンでの予測位置の方が適している場合もあるので、
					//そのターンと一つ前のターンで弾飛距離-敵距離の絶対値を比較し、より敵距離に近い方を採用し発射
					if (Math.abs((20 - 3 * btPower) * bulletGoingTime - Math.hypot(enemyX - myX, enemyY - myY))
							- previousBulletAndEnemyDifference >= 0) {
						getReadyToSnipe(getDegreeToIt(myX, myY, previousEnemyX, previousEnemyY) - gunHeading, btPower);
						return true;
					} else {
						getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
						return true;
					}
				}
			}
			previousEnemyX = enemyX;
			previousEnemyY = enemyY;
		}

		return false;
	}

	//角度パターン予測射撃
	public boolean angularVelPatternPredictionSnip(Enemy en, double btPower) {
		if (!adequateRecentLogJudge(en)) {
			return false;
		}
		if (uniformAngVelLinerMotionJudge(en)) {
			return false;
		}
		LinkedList<double[]> sampleLogData = sampleLogMaker(en);
		if (sampleLogData == null) {
			return false;
		}
		int matchedIndex = angularVelPatternMatchingIndex(en, sampleLogData); //敵ログのどの場所から予測開始するか
		if (matchedIndex == -1) {
			return false;
		}

		this.setBulletColor(Color.YELLOW);
		double myX = nextMe.x;
		double myY = nextMe.y;
		double enemyX = en.x;
		double enemyY = en.y;
		double enemyDeg = en.deg;
		double previousEnemyX = en.x;
		double previousEnemyY = en.y;

		double gunHeading = fixDegreeToCartesian(this.getGunHeading()); //直交座標系でのgunHeading
		double gunTurnTime = 0; //予測座標にgunを向けるのにかかる時間
		double bulletGoingTime = 0; //予測座標に射撃したと仮定したときの弾の飛ぶ時間
		double previousBulletGoingTime = 0; //予測ターンの一つ前のターンでのbulletGoingTime。そのターンと一つ前のターンでabs（弾飛距離-敵距離)を比較するときに使用

		Iterator<double[]> logIterator = sampleLogData.iterator();
		//予測ターンの前まではnext()で飛ばす
		for (int j = 0; j < matchedIndex; j++) {
			logIterator.next();
		}
		// 1~70turnまでの敵予測位置に対して射撃が間に合うものを探し、見つかった時点でreturn
		for (int i = 0; i < 70; i++) {
			double[] log = logIterator.next();
			enemyDeg += log[2];
			enemyX += log[0] * Math.cos(Math.toRadians(enemyDeg));
			enemyY += log[0] * Math.sin(Math.toRadians(enemyDeg));
			enemyX = overWallAdjustingX(enemyX);
			enemyY = overWallAdjustingY(enemyY);
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
			if ((20 - 3 * btPower) * bulletGoingTime >= Math.hypot(enemyX - myX, enemyY - myY)) {
				//turn数 == 1 なら即発射
				if (i == 1) {
					getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
					return true;
				} else {
					double previousBulletAndEnemyDifference = Math
							.abs((20 - 3 * btPower) * previousBulletGoingTime
									- Math.hypot(previousEnemyX - myX, previousEnemyY - myY));
					//gunの回転との兼ね合いによっては一つ前のターンでの予測位置の方が適している場合もあるので、
					//そのターンと一つ前のターンで弾飛距離-敵距離の絶対値を比較し、より敵距離に近い方を採用し発射
					if (Math.abs((20 - 3 * btPower) * bulletGoingTime - Math.hypot(enemyX - myX, enemyY - myY))
							- previousBulletAndEnemyDifference >= 0) {
						getReadyToSnipe(getDegreeToIt(myX, myY, previousEnemyX, previousEnemyY) - gunHeading, btPower);
						return true;
					} else {
						getReadyToSnipe(getDegreeToIt(myX, myY, enemyX, enemyY) - gunHeading, btPower);
						return true;
					}
				}
			}
			previousEnemyX = enemyX;
			previousEnemyY = enemyY;
		}

		return false;
	}

	//敵のログの内必要のないものを削除
	public void pattarnCleaning(Enemy en) {
		garbageEnemyLogsRemover();
		Iterator<LinkedList<double[]>> iterator = enemyLogsMap.get(en.name).iterator();
		while (iterator.hasNext()) {
			LinkedList<double[]> list = iterator.next();
			//サイズが120未満かつ最後尾のログ（今集めている途中のログ）以外のログを削除
			if (list.size() < 120 && iterator.hasNext()) {
				iterator.remove();
			}
		}
	}

	//2000turn以上姿の見えない敵のログを削除
	public void garbageEnemyLogsRemover() {
		long currentTime = this.getTime();
		for (Enemy en : enemysMap.values()) {
			if (en.time != 0 && currentTime - en.time >= 2000) {
				enemyLogsMap.remove(en.name);
			}
		}
	}

	//直近の敵が角度変化無しの等速直線運動をしているかどうかを判定
	public boolean uniformDegLinerMotionJudge(Enemy en) {
		double vel = en.recentLog.get(0)[0];
		double deg = en.recentLog.get(0)[1];
		double velSum = vel;
		double velDiffer = 0;
		double degDiffer = 0;
		for (int i = 1; i < RECENT_LOGSIZE; i++) {
			velSum += en.recentLog.get(i)[0];
			velDiffer += Math.abs(en.recentLog.get(i)[0] - vel);
			degDiffer += Math.abs(en.recentLog.get(i)[1] - deg);
		}
		if (velDiffer < 0.1 && degDiffer < 0.1 && velSum != 0) {
			return true;
		} else {
			return false;
		}
	}

	//直近の敵が角速度無しの等速直線運動をしているかどうかを判定
	public boolean uniformAngVelLinerMotionJudge(Enemy en) {
		double vel = en.recentLog.get(0)[0];
		double velSum = vel;
		double velDiffer = 0;
		double degDiffer = 0;
		for (int i = 1; i < RECENT_LOGSIZE; i++) {
			velSum += en.recentLog.get(i)[0];
			velDiffer += Math.abs(en.recentLog.get(i)[0] - vel);
			degDiffer += Math.abs(en.recentLog.get(i)[2]);
		}
		if (velDiffer < 0.1 && degDiffer < 0.1 && velSum != 0) {
			return true;
		} else {
			return false;
		}
	}

	//直近の敵が直線運動をしているかどうかを判定
	public boolean linerMotionJudge(Enemy en) {
		double degDiffer = 0;
		for (int i = 1; i < RECENT_LOGSIZE; i++) {
			degDiffer += Math.abs(en.recentLog.get(i)[2]);
		}
		if (degDiffer < 0.1) {
			return true;
		} else {
			return false;
		}
	}

	//過去ログの最後尾からさかのぼっていき、今の敵が180度角度を変えた次点でさかのぼったturn数-1を返す
	public int beforeReturnInterval(Enemy en, LinkedList<double[]> sampleLogData) {
		int intervalTurns = 0;
		Iterator<double[]> sampleLogIterator = sampleLogData.descendingIterator();
		double beforeDeg = sampleLogIterator.next()[1];
		for (int i = 0; i < 100 && sampleLogIterator.hasNext(); i++) {
			double nextDeg = sampleLogIterator.next()[1];
			double degDifference = Math.abs(nextDeg - beforeDeg);
			if (179.9 < degDifference && degDifference < 180.1) {
				break;
			}
			//直線運動でなくなったら異常終了
			else if (degDifference > 0.1) {
				intervalTurns = -1;
				break;
			}
			intervalTurns++;
		}
		return intervalTurns;
	}

	public LinkedList<double[]> recentLogList(Enemy en, int size) {
		ArrayList<LinkedList<double[]>> logsList = enemyLogsMap.get(en.name);
		if (logsList.isEmpty()) {
			return null;
		}
		LinkedList<double[]> sampleLogData = logsList.get(logsList.size() - 1);
		LinkedList<double[]> recentList = new LinkedList<double[]>();
		//直近のログサイズが反復運動のパターンサイズ未満のときはnullを返す
		if (sampleLogData.size() < size) {
			return null;
		}
		Iterator<double[]> sampleLogsIterator = sampleLogData.descendingIterator();
		for (int i = 0; i < size && sampleLogsIterator.hasNext(); i++) {
			recentList.addFirst(sampleLogsIterator.next().clone());
		}
		return recentList;
	}

	//過去ログから今の敵に一致する反復運動を検出した場合、その動きのlistのサイズを返す
	public int repetitiveListSize(Enemy en, LinkedList<double[]> sampleLogData) {
		if (en.recentLog.size() < RECENT_LOGSIZE) {
			return -1;
		}
		//直近の動きが直線運動でなければnullを返す
		if (!linerMotionJudge(en)) {
			return -1;
		}

		//ログの最後尾からturn前後の角度を比べることで反復運動の両端を割り出し、listに切り出す
		LinkedList<double[]> repetiList = new LinkedList<double[]>();
		boolean repetiReturning = false; //反復運動の折り返し中ならtrue
		boolean missionComplete = false; //listの切り出しに成功したかどうか

		Iterator<double[]> sampleLogDataIterator = sampleLogData.descendingIterator();
		repetiList.addFirst(sampleLogDataIterator.next().clone());

		while (sampleLogDataIterator.hasNext() && !missionComplete) {
			//角度が180度変わる地点まで進める
			while (sampleLogDataIterator.hasNext()) {
				double[] beforeLog = sampleLogDataIterator.next();
				double degDifference = Math.abs(beforeLog[1] - en.deg);
				if (179.9 < degDifference && degDifference < 180.1) {
					repetiList.clear();
					repetiList.addFirst(beforeLog.clone());
					break;
				} else {
					repetiList.clear();
					repetiList.addFirst(beforeLog.clone());
				}
			}

			//反復運動パターンの収集を始める
			while (sampleLogDataIterator.hasNext()) {
				double[] beforeLog = sampleLogDataIterator.next();
				double degDifference = Math.abs(beforeLog[1] - repetiList.getFirst()[1]);
				if (179.9 < degDifference && degDifference < 180.1) {
					repetiList.addFirst(beforeLog);
					repetiReturning = true;
					break;
				} else if (degDifference < 0.1) {
					repetiList.addFirst(beforeLog);
				}
				//反復直線運動でなければ中断し、やり直し
				else {
					repetiList.clear();
					repetiList.addFirst(beforeLog.clone());
					repetiReturning = false;
					break;
				}
			}

			//折り返しの反復運動パターン収集
			while (sampleLogDataIterator.hasNext() && repetiReturning) {
				double[] beforeLog = sampleLogDataIterator.next();
				double degDifference = Math.abs(beforeLog[1] - repetiList.getFirst()[1]);
				if (179.9 < degDifference && degDifference < 180.1) {
					missionComplete = true;
					break;
				} else if (degDifference < 0.1) {
					repetiList.addFirst(beforeLog);
				}
				//反復直線運動でなければ中断し、やり直し
				else {
					repetiList.clear();
					repetiList.addFirst(beforeLog.clone());
					break;
				}
			}
		}

		if (!missionComplete) {
			return -1;
		} else {
			return repetiList.size();
		}

	}

	public LinkedList<double[]> recentRepetitiveList(Enemy en, int size) {
		ArrayList<LinkedList<double[]>> logsList = enemyLogsMap.get(en.name);
		LinkedList<double[]> sampleLogData = logsList.get(logsList.size() - 1);
		LinkedList<double[]> repeList = new LinkedList<double[]>();
		//直近のログサイズが反復運動のパターンサイズ未満のときはnullを返す
		if (sampleLogData.size() < size) {
			return null;
		}
		Iterator<double[]> sampleLogsIterator = sampleLogData.descendingIterator();
		for (int i = 0; i < size && sampleLogsIterator.hasNext(); i++) {
			repeList.addFirst(sampleLogsIterator.next().clone());
		}
		//反復運動をしているか確認し、そうでない行動が入っていたらnullを返す
		Iterator<double[]> repeListIterator = repeList.iterator();
		while (repeListIterator.hasNext()) {
			double degDifference = Math.abs(repeListIterator.next()[1] - en.deg);
			if (degDifference < 0.1) {
				continue;
			} else if (179.9 < degDifference && degDifference < 180.1) {
				continue;
			} else {
				return null;
			}
		}

		return repeList;
	}

	//敵のログから見つけた次ターンの敵の動きを表す要素のインデックスを返す。見つからなかった場合は-1を戻す
	public int degPatternMatchingIndex(Enemy en, LinkedList<double[]> sampleLogData) {
		final int MATCHING_BORDER_LINE = 20;
		if (sampleLogData.size() < 90) {
			return -1;
		}
		Iterator<double[]> sampleLogDataIterator = sampleLogData.iterator();
		//予測に使うパターンを70turn分確保
		int sampleLogDataMargin = sampleLogData.size() - 70;
		//マッチングに消費したパターン数
		int usedPatterns = RECENT_LOGSIZE;
		//ログの中でマッチした行動の情報7turnの最後尾が入ったインデックス
		int matchedTurnIndex = RECENT_LOGSIZE;
		//計算のために過去ログから7turn分を入れておくリスト
		LinkedList<double[]> tmpStorageList = new LinkedList<double[]>();
		for (int i = 0; i < RECENT_LOGSIZE; i++) {
			tmpStorageList.add(sampleLogDataIterator.next());
		}
		//過去パターン - 直近パターンの絶対値。パターンが一致しているかどうかの指標
		double differ = 0;
		double tmpDiffer;
		for (int i = 0; i < RECENT_LOGSIZE; i++) {
			differ += Math.abs(tmpStorageList.get(i)[0] - en.recentLog.get(i)[0])
					+ Math.abs(tmpStorageList.get(i)[1] - en.recentLog.get(i)[1]);
		}
		//予測に使うパターンを70turn分残し、マッチングさせていく。すでに0～7indexは計算したので、1～8から開始
		while (usedPatterns < sampleLogDataMargin) {
			usedPatterns++;
			tmpDiffer = 0;
			//7turn分が入った過去ログ一時保管リストを一つ前に進める
			tmpStorageList.removeFirst();
			tmpStorageList.add(sampleLogDataIterator.next());
			for (int i = 0; i < RECENT_LOGSIZE; i++) {
				tmpDiffer += Math.abs(tmpStorageList.get(i)[0] - en.recentLog.get(i)[0])
						+ Math.abs(tmpStorageList.get(i)[1] - en.recentLog.get(i)[1]);
			}
			if (differ > tmpDiffer) {
				differ = tmpDiffer;
				matchedTurnIndex = usedPatterns;
			}
		}
		if (differ > MATCHING_BORDER_LINE) {
			return -1;
		}
		return matchedTurnIndex;
	}

	//敵のログから見つけた次ターンの敵の動きを表す要素のインデックスを返す。見つからなかった場合は-1を戻す
	public int angularVelPatternMatchingIndex(Enemy en, LinkedList<double[]> sampleLogData) {
		final int MATCHING_BORDER_LINE = 20;
		if (sampleLogData.size() < 90) {
			return -1;
		}
		Iterator<double[]> sampleLogDataIterator = sampleLogData.iterator();
		//予測に使うパターンを70turn分確保
		int sampleLogDataMargin = sampleLogData.size() - 70;
		//マッチングに消費したパターン数
		int usedPatterns = RECENT_LOGSIZE;
		//ログの中でマッチした行動の情報7turnの最後尾が入ったインデックス
		int matchedTurnIndex = RECENT_LOGSIZE;
		//計算のために過去ログから7turn分を入れておくリスト
		LinkedList<double[]> tmpStorageList = new LinkedList<double[]>();
		for (int i = 0; i < RECENT_LOGSIZE; i++) {
			tmpStorageList.add(sampleLogDataIterator.next());
		}
		//過去パターン - 直近パターンの絶対値。パターンが一致しているかどうかの指標
		double differ = 0;
		double tmpDiffer;
		for (int i = 0; i < RECENT_LOGSIZE; i++) {
			differ += Math.abs(tmpStorageList.get(i)[0] - en.recentLog.get(i)[0])
					+ Math.abs(tmpStorageList.get(i)[2] - en.recentLog.get(i)[2]);
		}
		//予測に使うパターンを70turn分残し、マッチングさせていく。すでに0～7indexは計算したので、1～8から開始
		while (usedPatterns < sampleLogDataMargin) {
			usedPatterns++;
			tmpDiffer = 0;
			//7turn分が入った過去ログ一時保管リストを一つ前に進める
			tmpStorageList.removeFirst();
			tmpStorageList.add(sampleLogDataIterator.next());
			for (int i = 0; i < RECENT_LOGSIZE; i++) {
				tmpDiffer += Math.abs(tmpStorageList.get(i)[0] - en.recentLog.get(i)[0])
						+ Math.abs(tmpStorageList.get(i)[2] - en.recentLog.get(i)[2]);
			}
			if (differ > tmpDiffer) {
				differ = tmpDiffer;
				matchedTurnIndex = usedPatterns;
			}
		}
		if (differ > MATCHING_BORDER_LINE) {
			return -1;
		}
		return matchedTurnIndex;
	}

	//敵と壁の斥力を距離減衰を考慮してからベクトル合成して移動すべき角度を求める。戻り値は直交座標系での最終的なベクトルの角度
	public double antiGravityVector() {
		long currentTime = this.getTime();
		double myX = this.getX();
		double myY = this.getY();
		double force = 0; //途中式で使う敵が自分に及ぼす斥力ベクトルの大きさ
		double enemyRadian = 0; //途中式で使う自分から見た敵の角度
		double componentX = 0; //自分が受けている斥力のx成分合計
		double componentY = 0; //自分が受けている斥力のy成分合計
		double vectorDegree = 0; // 最終的なベクトルの角度

		//全敵のgravPower（斥力）をベクトル合成
		for (Enemy en : enemysMap.values()) {
			//敵が生きていなかったら飛ばす
			if (!en.living) {
				continue;
			}
			//敵x座標またはy座標にNaNが入っていたら飛ばす
			if (java.lang.Double.isNaN(en.x) || java.lang.Double.isNaN(en.y)) {
				continue;
			}
			//敵の斥力を距離の2乗で割ったものをx,y成分に分解し合計していく。距離が36px未満の場合は36pxとする
			if (Math.hypot(en.x - myX, en.y - myY) < 36) {
				force = en.gravPower / Math.pow(36, 2);
			} else {
				force = en.gravPower / Math.pow(Math.hypot(en.x - myX, en.y - myY), 2);
			}
			//forceが負の無限大にいったときはcontinue
			if (java.lang.Double.isInfinite(force)) {
				continue;
			}
			enemyRadian = Math.toRadians(getDegreeToIt(myX, myY, en.x, en.y));
			//forceはマイナス値でありすでにベクトルが反転しているため、この計算で正しい斥力ベクトルの成分になる
			componentX += force * Math.cos(enemyRadian);
			componentY += force * Math.sin(enemyRadian);
		}

		//lockonしている敵がいる方に射線を45度避けて押し出すように斥力を足す
		if (lockOnMode) {
			if (lockOnEnemy.distance >= 150 && meleeMode) {
				force = 5 / Math.pow(36, 2);
				enemyRadian = Math.toRadians(getDegreeToIt(myX, myY, lockOnEnemy.x, lockOnEnemy.y) + 45);
				componentX += force * Math.cos(enemyRadian);
				componentY += force * Math.sin(enemyRadian);
			}
		}

		//全敵弾道からの斥力をベクトル合成
		Iterator<EnemyBullet> enemyBulletIterator = enemysBulletsList.iterator();
		//弾道が一つも無ければさらに斥力点を追加
		if (!enemyBulletIterator.hasNext() && lockOnMode) {
			if (lockOnEnemy.distance > 77) {
				//自分付近に乱数(turn毎にランダムで更新される値)で仮想斥力点を設置
				force = 5 / Math.pow(18, 2);
				enemyRadian = Math.toRadians(
						getDegreeToIt(myX, myY, lockOnEnemy.x, lockOnEnemy.y) + 360 * randomNum);
				componentX += force * Math.cos(enemyRadian);
				componentY += force * Math.sin(enemyRadian);
			}
			if (lockOnEnemy.distance >= 300) {
				force = 20 / Math.pow(36, 2);
				enemyRadian = Math.toRadians(getDegreeToIt(myX, myY, lockOnEnemy.x, lockOnEnemy.y) + 45);
				componentX += force * Math.cos(enemyRadian);
				componentY += force * Math.sin(enemyRadian);
			}
		}
		while (enemyBulletIterator.hasNext()) {
			EnemyBullet enbt = enemyBulletIterator.next();
			//期限切れの弾を消去
			if (enbt.remainTurn <= currentTime) {
				enemyBulletIterator.remove();
			}
			//予想到達ターンが20以上先の遠すぎる弾は処理しない
			if (enbt.remainTurn + 1 - currentTime >= 20) {
				continue;
			}

			double gravityPower = enbt.gravPower;
			double radianBulletStartToMe = Math.atan2(myY - enbt.startY, myX - enbt.startX);
			//弾道角と弾発射位置から自分までの角度の差分
			double radianBetweenBulletLineAndMe = radianBulletStartToMe - Math.toRadians(enbt.bulletDegree);
			//弾道から垂直に自分へと延びてくるベクトル。符号と大きさのみ利用する。符号が正なら弾道の右側、負なら左側にいることが判定できる
			double distance = Math.hypot(myX - enbt.startX, myY - enbt.startY)
					* Math.sin(radianBetweenBulletLineAndMe);
			//distanceが0なら少し自分の位置をずらして再計算
			if (distance == 0) {
				radianBulletStartToMe = Math.atan2(myY + 0.1 - enbt.startY, myX + 0.1 - enbt.startX);
				//弾道角と弾発射位置から自分までの角度の差分
				radianBetweenBulletLineAndMe = radianBulletStartToMe - Math.toRadians(enbt.bulletDegree);
				//弾道から垂直に自分へと延びてくるベクトル。符号と大きさのみ利用する。符号が正なら弾道の右側、負なら左側にいることが判定できる
				distance = Math.hypot(myX + 0.1 - enbt.startX, myY + 0.1 - enbt.startY)
						* Math.sin(radianBetweenBulletLineAndMe);
			}
			//弾道の左側にいるなら、最終的なベクトルが反転するようにあらかじめgravityPowerを負の値に
			if (distance < 0) {
				gravityPower *= -1;
			}
			distance = Math.abs(distance);
			//自分に近すぎる弾道は影響が大きすぎて他の脅威を無視してしまうため、最低でも36pxの距離とする
			if (distance < 36) {
				distance = 36;
			}

			double tmpComponentX = gravityPower * Math.cos(Math.toRadians(enbt.bulletDegree + 90))
					/ Math.pow(distance, 2);
			double tmpComponentY = gravityPower * Math.sin(Math.toRadians(enbt.bulletDegree + 90))
					/ Math.pow(distance, 2);

			//どちらかの値がNaNまたは負の無限大にいったときはcontinue
			if (java.lang.Double.isInfinite(tmpComponentX) || java.lang.Double.isInfinite(tmpComponentY)
					|| java.lang.Double.isNaN(tmpComponentX) || java.lang.Double.isNaN(tmpComponentY)) {
				continue;
			}
			componentX += tmpComponentX;
			componentY += tmpComponentY;

		}

		//乱戦状態ならばフィールド中央に仮想敵設置
		if (meleeMode) {
			force = -2000 / Math.pow(Math.hypot(fieldWidth / 2 - myX, fieldHeight / 2 - myY), 2);
			if (!java.lang.Double.isInfinite(force)) {
				enemyRadian = Math.toRadians(getDegreeToIt(myX, myY, fieldWidth / 2, fieldHeight / 2));
				componentX += force * Math.cos(enemyRadian);
				componentY += force * Math.sin(enemyRadian);
			}
		}

		//borderGuardが守るラインからの斥力を距離の2乗で割ったものを合成。既にborderGuardの守るラインに入ってしまっている場合は、距離を1pxとして処理
		double wallForce = 1000;
		//左壁
		if (myX <= BorderGuardLine + 18) {
			componentX += wallForce / Math.pow(5, 2);
		} else {
			componentX += wallForce / Math.pow(myX - BorderGuardLine, 2);
		}
		//下壁
		if (myY <= BorderGuardLine + 18) {
			componentY += wallForce / Math.pow(5, 2);
		} else {
			componentY += wallForce / Math.pow(myY - BorderGuardLine, 2);
		}
		//右壁
		if (myX >= fieldWidth - BorderGuardLine - 18) {
			componentX -= wallForce / Math.pow(5, 2);
		} else {
			componentX -= wallForce / Math.pow(fieldWidth - BorderGuardLine - myX, 2);
		}
		//上壁
		if (myY >= fieldHeight - BorderGuardLine - 18) {
			componentY -= wallForce / Math.pow(5, 2);
		} else {
			componentY -= wallForce / Math.pow(fieldHeight - BorderGuardLine - myY, 2);
		}

		vectorDegree = Math.toDegrees(Math.atan2(componentY, componentX));
		return vectorDegree;
	}
}
